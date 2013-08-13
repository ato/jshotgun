package com.barbarysoftware.watchservice;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.TimeUnit;

public abstract class MacOSXWatchService implements Closeable, java.nio.file.WatchService {


    public static WatchService newWatchService() {
        final String osVersion = System.getProperty("os.version");
        final int minorVersion = Integer.parseInt(osVersion.split("\\.")[1]);
        if (minorVersion < 5) {
            // for Mac OS X prior to Leopard (10.5)
            return new MacOSXPollingWatchService();
            
        } else {
            // for Mac OS X Leopard (10.5) and upwards
            return new MacOSXListeningWatchService();
        }
    }

}
