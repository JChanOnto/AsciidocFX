package com.kodedu.service;

import com.kodedu.helper.IOHelper;
import org.asciidoctor.Asciidoctor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.IntStream;

import static org.springframework.core.Ordered.HIGHEST_PRECEDENCE;

@Component
public class AsciidoctorFactory {
    // Each latch flips when (a) its bean has been instantiated AND
    // (b) its `requireLibrary(...)` call has finished — i.e. the
    // backend converter is actually registered.  See SpringAppConfig:
    // requireLibrary is now invoked synchronously inside each bean
    // factory, so "bean is resolvable" === "backend is ready".
    private static CountDownLatch plainDoctorReady = new CountDownLatch(1);
    private static CountDownLatch revealDoctorReady = new CountDownLatch(1);
    private static CountDownLatch htmlDoctorReady = new CountDownLatch(1);
    private static CountDownLatch nonHtmlDoctorReady = new CountDownLatch(1);
    private static Asciidoctor plainDoctor;
    private static Asciidoctor revealDoctor;
    private static Asciidoctor htmlDoctor;
    private static Asciidoctor nonHtmlDoctor;
    private static DirectoryService directoryService;
    private static Map<Asciidoctor, UserExtension> userExtensionMap = new ConcurrentHashMap<>();

    private static BlockingQueue<Asciidoctor> blockingQueue = new LinkedBlockingQueue<>(4);

    @EventListener
    @Order(HIGHEST_PRECEDENCE)
    public void handleContextRefreshEvent(ContextRefreshedEvent event) {
        ApplicationContext context = event.getApplicationContext();
        Thread.startVirtualThread(() -> {
            initializeDoctors();
            Thread.startVirtualThread(() -> {
                directoryService = context.getBean(DirectoryService.class);
            });
            // Resolve the four lazy doctor beans on parallel virtual
            // threads.  Each bean factory now blocks on its own
            // requireLibrary(...) so the latch flips only when that
            // backend is genuinely ready to convert.  Running them in
            // parallel preserves the startup throughput the previous
            // (racy) async-require hack was buying us, without the
            // race.
            Thread.startVirtualThread(() -> {
                plainDoctor = context.getBean("plainDoctor", Asciidoctor.class);
                plainDoctorReady.countDown();
            });
            Thread.startVirtualThread(() -> {
                htmlDoctor = context.getBean("htmlDoctor", Asciidoctor.class);
                htmlDoctorReady.countDown();
            });
            Thread.startVirtualThread(() -> {
                nonHtmlDoctor = context.getBean("nonHtmlDoctor", Asciidoctor.class);
                nonHtmlDoctorReady.countDown();
            });
            Thread.startVirtualThread(() -> {
                revealDoctor = context.getBean("revealDoctor", Asciidoctor.class);
                revealDoctorReady.countDown();
            });
        });
    }

    private static void checkUserExtensions(Asciidoctor doctor) {
        if (Objects.isNull(directoryService)) {
            return;
        }
        Path workingDir = directoryService.workingDirectory();

        // 1) Collect extensions from the conventional .asciidoctor/lib directory
        List<Path> extensions = new ArrayList<>();
        Path libDir = workingDir.resolve(".asciidoctor/lib");
        if (Files.exists(libDir)) {
            extensions.addAll(IOHelper.walk(libDir, 2)
                    .filter(p -> p.toString().endsWith(".rb") || p.toString().endsWith(".jar"))
                    .sorted().toList());
        }

        // 2) Auto-discover Asciidoctor Ruby extensions elsewhere in the project
        //    (validated by content — must reference Asciidoctor::Extensions).
        //    Skip files already found in .asciidoctor/lib to avoid duplicates.
        Set<String> alreadyLoaded = extensions.stream()
                .map(p -> p.getFileName().toString())
                .collect(java.util.stream.Collectors.toSet());
        List<Path> discovered = ProjectConfigDiscovery.resolveRubyExtensions(workingDir);
        for (Path ext : discovered) {
            if (!alreadyLoaded.contains(ext.getFileName().toString())) {
                extensions.add(ext);
            }
        }

        if (extensions.isEmpty()) {
            UserExtension userExtension = userExtensionMap.get(doctor);
            if (Objects.nonNull(userExtension)) {
                userExtension.registerExtensions(doctor, new ArrayList<>());
            }
            return;
        }

        UserExtension userExtension = userExtensionMap.compute(doctor, (adoc, uEx) -> {
            if (Objects.nonNull(uEx)) {
                return uEx;
            }
            UserExtension extension = new UserExtension();
            extension.setExtensionGroup(adoc.createGroup());
            return extension;
        });
        userExtension.registerExtensions(doctor, extensions);
    }

    public static Asciidoctor getHtmlDoctor() {
        waitLatch(htmlDoctorReady);
        checkUserExtensions(htmlDoctor);
        return htmlDoctor;
    }

    public static Asciidoctor getNonHtmlDoctor() {
        waitLatch(nonHtmlDoctorReady);
        checkUserExtensions(nonHtmlDoctor);
        return nonHtmlDoctor;
    }

    public static Asciidoctor getPlainDoctor() {
        waitLatch(plainDoctorReady);
        checkUserExtensions(plainDoctor);
        return plainDoctor;
    }

    public static Asciidoctor getRevealDoctor() {
        waitLatch(revealDoctorReady);
        checkUserExtensions(revealDoctor);
        return revealDoctor;
    }

    /**
     * True iff the non-HTML (PDF / DocBook) backend has finished
     * initialising.  UI affordances like the PDF-preview pane's
     * loading indicator poll this to decide whether to show a
     * "warming up" hint before the user can trigger a render.
     */
    public static boolean isNonHtmlDoctorReady() {
        return nonHtmlDoctorReady.getCount() == 0;
    }

    /**
     * Block the caller until the non-HTML backend is initialised.
     * Intended for off-FX-thread use (e.g., a virtual thread that
     * clears a startup indicator once the PDF pipeline is warm).
     */
    public static void awaitNonHtmlDoctorReady() {
        waitLatch(nonHtmlDoctorReady);
    }

    public void initializeDoctors() {
        Thread.startVirtualThread(() -> {
            IntStream.rangeClosed(1, 4)
                    .forEach(i -> {
                        Asciidoctor asciidoctor = Asciidoctor.Factory.create();
                        blockingQueue.add(asciidoctor);
                    });
        });
    }

    public static Asciidoctor getAsciidoctor() {
        Asciidoctor doctor;
        try {
            doctor = blockingQueue.take();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        return doctor;
    }

    private static void waitLatch(CountDownLatch countDownLatch) {
        try {
            countDownLatch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

}
