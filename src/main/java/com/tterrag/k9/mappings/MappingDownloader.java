package com.tterrag.k9.mappings;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.io.FileUtils;

import com.google.common.base.Charsets;
import com.google.common.io.Files;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.tterrag.k9.util.NullHelper;
import com.tterrag.k9.util.annotation.NonNull;
import com.tterrag.k9.util.annotation.Nullable;

import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
@Slf4j
public abstract class MappingDownloader<M extends Mapping, T extends MappingDatabase<M>> {
    
    @FunctionalInterface
    public interface DatabaseFactory<@NonNull T> {

        T create(String version) throws NoSuchVersionException;

    }
        
    private static final String VERSION_FILE = ".dataversion";
    
    private final Path dataFolder = Paths.get(".", "data");
    
    private final String folder;
    private final DatabaseFactory<T> dbFactory;
    private final int version;
    
    private final Map<String, T> mappingTable = new HashMap<>();
    
    private volatile long lastVersionCheck;
    private final Object2LongMap<String> lastChecked = new Object2LongOpenHashMap<>();
    
    protected abstract Mono<Void> updateVersions();
    
    protected abstract Mono<Void> checkUpdates(String version);
    
    protected abstract Set<String> getMinecraftVersionsInternal();
    
    protected abstract String getLatestMinecraftVersionInternal();
    
    private Mono<Void> updateVersionsIfRequired() {
        Mono<Void> updateCheck = Mono.empty();
        if (lastVersionCheck + TimeUnit.HOURS.toMillis(1) < System.currentTimeMillis()) {
            lastVersionCheck = System.currentTimeMillis();
            updateCheck = updateVersions();
        }
        return updateCheck;
    }
    
    public final Flux<String> getMinecraftVersions() {
        return updateVersionsIfRequired().thenMany(Flux.fromStream(() -> getMinecraftVersionsInternal().stream()));
    }
    
    public final Mono<String> getLatestMinecraftVersion() {
        return updateVersionsIfRequired().then(Mono.fromSupplier(() -> getLatestMinecraftVersionInternal()));
    }
    
    public Path getDataFolder() {
        return dataFolder.resolve(folder);
    }
    
    protected void collectParsers(GsonBuilder builder) {}
    
    @Nullable
    private Gson gson; // lazy load
    protected Gson getGson() {
        Gson ret = this.gson;
        if (ret == null) {
            GsonBuilder builder = new GsonBuilder();
            collectParsers(builder);
            ret = this.gson = NullHelper.notnullL(builder.create(), "GsonBuilder#create");
        }
        return ret;
    }
    
    private static final AtomicBoolean hasCleanedUp = new AtomicBoolean(false);

    @SneakyThrows
    public void start() {
        dataFolder.toFile().mkdirs();
        
        // Nuke all non-directories, and directories without a version file. Do this only once globally.
        if (!hasCleanedUp.getAndSet(true)) {
            File[] folders = dataFolder.toFile().listFiles();
            for (File folder : folders) {
                if (folder.isDirectory()) {
                    File versionfile = new File(folder, VERSION_FILE);
                    if (!versionfile.exists()) {
                        log.info("Deleting outdated data found in " + folder);
                        FileUtils.deleteDirectory(folder);
                    }
                } else {
                    log.warn("Found unknown file " + folder + " in data folder. Deleting!");
                    folder.delete();
                }
            }
        }
        
        // Check this download folder's version file, if it is nonexistent or out of date, delete it and start fresh
        File versionfile = getDataFolder().resolve(VERSION_FILE).toFile();
        int currentVersion = -1;
        if (versionfile.exists()) {
            try {
                currentVersion = Integer.parseInt(Files.asCharSource(versionfile, StandardCharsets.UTF_8).readFirstLine());
            } catch (Exception e) {
                log.error("Invalid " + VERSION_FILE + ": " + versionfile, e);
            }
        }
        if (currentVersion < version) {
            File dataDir = getDataFolder().toFile();
            if (dataDir.exists()) {
                log.info("Found outdated data in folder " + dataDir + " (" + currentVersion + " < " + version + "). Deleting!");
                FileUtils.deleteDirectory(dataDir);
            } else {
                log.info("Creating new data folder " + dataDir);
            }
            dataDir.mkdir();
            FileUtils.write(new File(dataDir, VERSION_FILE), Integer.toString(version), Charsets.UTF_8);
        }
    }
    
    protected void remove(String mcver) {
        synchronized (mappingTable) {
            mappingTable.remove(mcver);
        }
    }
    
    private T get(String mcver) {
        synchronized (mappingTable) {
            return mappingTable.get(mcver);
        }
    }
    
    private T put(String mcver, T db) {
        synchronized (mappingTable) {
            return mappingTable.put(mcver, db);
        }
    }
    
    public Mono<Void> forceUpdateCheck(String mcver) {
        synchronized (lastChecked) {
            lastChecked.removeLong(mcver);
        }
        return checkUpdateIfRequired(mcver);
    }
    
    private Mono<Void> checkUpdateIfRequired(String mcver) {
        Mono<Void> updateCheck = Mono.empty();
        synchronized (lastChecked) {
            long checked = lastChecked.getLong(mcver);
            if (checked + TimeUnit.HOURS.toMillis(1) < System.currentTimeMillis()) {
                updateCheck = checkUpdates(mcver).doOnError($ -> {
                    synchronized (lastChecked) {
                        lastChecked.removeLong(mcver);
                    }
                });
                lastChecked.put(mcver, System.currentTimeMillis());
            }
        }
        return updateCheck;
    }

    public Mono<T> getDatabase(String mcver) {
        boolean force; // If this is the first lookup on this version, wait for the initial update check to complete,
                       // otherwise files might be missing
        synchronized (lastChecked) {
            force = !lastChecked.containsKey(mcver);
        }
        Mono<Void> updateCheck = checkUpdateIfRequired(mcver);
        if (!force) {
            updateCheck.subscribe(); // This is not a forced update, so we can use old data for now. So intentionally
                                     // run this "to the side", the mappings will update naturally when this completes
                                     // as the DB will be removed.
            updateCheck = Mono.empty();
        }

        return updateCheck.then(Mono.fromSupplier(() -> get(mcver)))
                .switchIfEmpty(Mono.fromCallable(() -> dbFactory.create(mcver))
                        .flatMap(db -> Mono.fromCallable(() -> db.reload()).thenReturn(db))
                        .doOnNext(db -> put(mcver, db)));
    }
    
    public Flux<M> lookup(String name, String mcver) {
        return getDatabase(mcver).flatMapIterable(db -> db.lookup(name));
    }
    
    public Flux<M> lookup(MappingType type, String name, String mcver) {
        return getDatabase(mcver).flatMapIterable(db -> db.lookup(type, name));
    }
}
