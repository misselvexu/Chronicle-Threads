package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Background thread to monitor disk space free.
 */
public enum DiskSpaceMonitor implements Runnable {
    INSTANCE;

    final Map<File, FileStore> fileStoreCacheMap = new ConcurrentHashMap<>();
    final Map<FileStore, DiskAttributes> diskAttributesMap = new ConcurrentHashMap<>();
    final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("disk-space-checker", true, Thread.MIN_PRIORITY));
    private int thresholdPercentage;

    DiskSpaceMonitor() {
        executor.scheduleAtFixedRate(this, 1, 1, TimeUnit.SECONDS);
    }

    // used for testing purposes
    public void clear() {
        fileStoreCacheMap.clear();
        diskAttributesMap.clear();
    }

    public void pollDiskSpace(File file) {
        FileStore fs = fileStoreCacheMap.get(file);
        if (fs == null) {
            if (file.exists()) {
                Path path = file.getAbsoluteFile().toPath();
                try {
                    fs = Files.getFileStore(path);
                    fileStoreCacheMap.put(file, fs);
                } catch (IOException e) {
                    Jvm.warn().on(getClass(), "Error trying to obtain the FileStore for " + path, e);
                    return;
                }
            } else {
                // nothing to monitor if it doesn't exist.
                return;
            }
        }
        DiskAttributes da = diskAttributesMap.computeIfAbsent(fs, DiskAttributes::new);
        da.polled = true;
    }

    @Override
    public void run() {
        for (Iterator<DiskAttributes> iterator = diskAttributesMap.values().iterator(); iterator.hasNext(); ) {
            DiskAttributes da = iterator.next();
            try {
                da.run();
            } catch (IOException e) {
                Jvm.warn().on(getClass(), "Unable to get disk space for " + da.fileStore, e);
                iterator.remove();
            }
        }
    }

    public int getThresholdPercentage() {
        return thresholdPercentage;
    }

    public void setThresholdPercentage(int thresholdPercentage) {
        this.thresholdPercentage = thresholdPercentage;
    }

    static class DiskAttributes {
        volatile boolean polled;
        long timeNextCheckedMS;
        long totalSpace;
        private FileStore fileStore;

        DiskAttributes(FileStore fileStore) {
            this.fileStore = fileStore;
        }

        void run() throws IOException {
            long now = System.currentTimeMillis();
            if (timeNextCheckedMS > now || !polled)
                return;

            polled = false;
            long start = System.nanoTime();
            if (totalSpace <= 0)
                totalSpace = fileStore.getTotalSpace();

            long unallocatedBytes = fileStore.getUnallocatedSpace();
            if (unallocatedBytes < (200 << 20)) {
                // if less than 200 Megabytes
                Jvm.warn().on(getClass(), "your disk " + fileStore + " is almost full, " +
                        "warning: chronicle-queue may crash if it runs out of space.");

            } else if (unallocatedBytes < totalSpace * DiskSpaceMonitor.INSTANCE.thresholdPercentage / 100) {
                double diskSpaceFull = 1000 * (totalSpace - unallocatedBytes) / totalSpace / 10.0;
                Jvm.warn().on(getClass(), "your disk " + fileStore
                        + " is " + diskSpaceFull + "% full, " +
                        "warning: chronicle-queue may crash if it runs out of space.");

            } else {
                timeNextCheckedMS = now + (unallocatedBytes >> 30); // wait 1 sec per GB free.
            }
            long time = System.nanoTime() - start;
            if (time > 1_000_000)
                Jvm.debug().on(getClass(), "Took " + time / 10_000 / 100.0 + " ms to check the disk space of " + fileStore);
        }
    }
}
