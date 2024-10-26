package org.telegram.messenger;
import android.content.Context;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import androidx.annotation.Nullable;
import fi.iki.elonen.NanoHTTPD;

public class VideoServer extends NanoHTTPD {
    private static volatile VideoServer instance;
    private File video;
    private final Object lock = new Object();
    private long lastUsed;
    private long serverStartTime;
    
    // Configuration constants
    public static final int PORT = 8900;
    private static final int BUFFER_SIZE = 8192;//16384; // Increased buffer size
    private static final long AUTO_STOP_MS = 30 * 60 * 1000;
    private static final String TAG = "Telegram cast VideoServer";
    private static final boolean DEBUG = BuildConfig.DEBUG; // Use build config for debug flag
    
    // Statistics
    private final AtomicLong bytesServed = new AtomicLong(0);
    private final AtomicLong requestsServed = new AtomicLong(0);
    private final AtomicLong rangeRequestsServed = new AtomicLong(0);
    
    public VideoServer(int port) {
        super(port);
        if (DEBUG) Log.d(TAG, String.format("VideoServer created on port %d", port));
    }
    
    public static VideoServer get() {
        VideoServer local = instance;
        if (local == null) {
            synchronized (VideoServer.class) {
                local = instance;
                if (local == null) {
                    if (DEBUG) Log.d(TAG, "Creating new VideoServer instance");
                    instance = local = new VideoServer(PORT);
                    local.startWatchdog();
                }
            }
        }
        return local;
    }
    
    private void startWatchdog() {
        if (DEBUG) Log.d(TAG, "Starting watchdog thread");
        Thread t = new Thread(() -> {
            while (true) {
                sleep(60_000);
                if (isAlive()) {
                    long idleTime = System.currentTimeMillis() - lastUsed;
                    if (DEBUG) Log.v(TAG, String.format("Watchdog check - Idle time: %d ms", idleTime));
                    if (idleTime > AUTO_STOP_MS) {
                        Log.i(TAG, String.format("Auto-stopping server after %d minutes idle. Stats: %s",
                            idleTime / 60000, getServerStats()));
                        stop();
                    }
                }
            }
        }, "VideoWatchdog");
        t.setDaemon(true);
        t.start();
    }
    
    private String getServerStats() {
        long uptime = System.currentTimeMillis() - serverStartTime;
        return String.format(Locale.US,
            "Uptime: %d min, Bytes: %d, Requests: %d, Range requests: %d",
            uptime / 60000, bytesServed.get(), requestsServed.get(), rangeRequestsServed.get());
    }
    
    public String start(Context context, File mp4) {
        synchronized (lock) {
            if (context == null || mp4 == null || !isValidFile(mp4)) {
                Log.w(TAG, String.format("Invalid input params: context=%s, file=%s",
                    context != null ? "non-null" : "null",
                    mp4 != null ? mp4.getAbsolutePath() : "null"));
                return null;
            }
            
            if (DEBUG) Log.d(TAG, String.format("Starting server for file: %s", mp4.getAbsolutePath()));
            
            stop();
            serverStartTime = System.currentTimeMillis();
            lastUsed = serverStartTime;
            File file = new File(ApplicationLoader.applicationContext.getFilesDir(), "video.mp4");
            file.delete();
            video = VideoScaler.scaleVideoIfNecessary(mp4, file);
            bytesServed.set(0);
            requestsServed.set(0);
            rangeRequestsServed.set(0);
            
            try {
                start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
                String url = getUrl(context);
                if (DEBUG) Log.d(TAG, String.format("Server started successfully, URL: %s", url));
                return url;
            } catch (IOException e) {
                Log.e(TAG, "Failed to start server", e);
                stop();
                return null;
            }
        }
    }
    
    public String getUrl(Context context) {
        if (!isAlive() || context == null) {
            if (DEBUG) Log.d(TAG, String.format("getUrl failed: alive=%b, context=%s",
                isAlive(), context != null ? "non-null" : "null"));
            return null;
        }
        lastUsed = System.currentTimeMillis();
        
        return getCurrentLocalIp(context) + "/video.mp4";
    }
    
    @Nullable
    public static String getCurrentLocalIp(final Context context) {
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
            if (wifi == null) {
                Log.w(TAG, "Failed to get WifiManager");
                return null;
            }
            
            int ip = wifi.getConnectionInfo().getIpAddress();
            if (ip == 0) {
                Log.w(TAG, "Failed to get valid IP address");
                return null;
            }
            
            String url = String.format(
                Locale.US, "http://%d.%d.%d.%d:%d",
                (ip & 0xff), (ip >> 8 & 0xff),
                (ip >> 16 & 0xff), (ip >> 24 & 0xff), PORT);
            
            if (DEBUG) Log.d(TAG, String.format("Generated URL: %s", url));
            return url;
        } catch (Exception e) {
            Log.e(TAG, "Failed to get URL", e);
            return null;
        }
    }
    
    private boolean isValidFile(File file) {
        boolean valid = file != null && file.exists() && file.canRead() &&
            file.isFile() && file.getName().toLowerCase().endsWith(".mp4");
        if (DEBUG && !valid) {
            Log.d(TAG, String.format("File validation failed: exists=%b, canRead=%b, isFile=%b, isMp4=%b",
                file != null && file.exists(),
                file != null && file.canRead(),
                file != null && file.isFile(),
                file != null && file.getName().toLowerCase().endsWith(".mp4")));
        }
        return valid;
    }
    
    @Override
    public Response serve(IHTTPSession session) {
        lastUsed = System.currentTimeMillis();
        requestsServed.incrementAndGet();
        
        if (DEBUG) {
            Log.d(TAG, String.format("Serving request: %s %s",
                session.getMethod(), session.getUri()));
            for (Map.Entry<String, String> header : session.getHeaders().entrySet()) {
                Log.v(TAG, String.format("Header: %s = %s",
                    header.getKey(), header.getValue()));
            }
        }
        
        if (video == null || !video.exists()) {
            Log.w(TAG, "Video file not found or null");
            return newFixedLengthResponse(Response.Status.NOT_FOUND,
                MIME_PLAINTEXT, "Video file not found");
        }
        
        FileInputStream fis = null;
        try {
            long fileLength = video.length();
            String rangeHeader = session.getHeaders().get("range");
            
            // Parse range header
            long start = 0;
            long end = fileLength - 1;
            boolean isRangeRequest = false;
            
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                isRangeRequest = true;
                rangeRequestsServed.incrementAndGet();
                
                try {
                    String[] ranges = rangeHeader.substring(6).split("-");
                    start = Long.parseLong(ranges[0]);
                    
                    if (ranges.length > 1 && !ranges[1].isEmpty()) {
                        end = Long.parseLong(ranges[1]);
                    }
                    
                    if (DEBUG) Log.d(TAG, String.format("Range request: bytes=%d-%d", start, end));
                    
                    // Validate range
                    if (start >= fileLength || start > end || end >= fileLength) {
                        Log.w(TAG, String.format("Invalid range request: start=%d, end=%d, length=%d",
                            start, end, fileLength));
                        return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE,
                            MIME_PLAINTEXT, "Requested range not satisfiable");
                    }
                } catch (NumberFormatException e) {
                    Log.w(TAG, "Invalid range format: " + rangeHeader);
                    start = 0;
                    end = fileLength - 1;
                    isRangeRequest = false;
                }
            }
            
            // Create custom input stream for range support
            fis = new FileInputStream(video);
            if (start > 0) {
                fis.skip(start);
            }
            
            long contentLength = end - start + 1;
            Response response;
            
            if (isRangeRequest) {
                response = newChunkedResponse(Response.Status.PARTIAL_CONTENT,
                    "video/mp4",
                    new BoundedInputStream(fis, contentLength, bytesServed));
                
                response.addHeader("Content-Range",
                    String.format("bytes %d-%d/%d", start, end, fileLength));
                
                if (DEBUG) Log.d(TAG, String.format("Serving partial content: bytes %d-%d/%d",
                    start, end, fileLength));
            } else {
                response = newChunkedResponse(Response.Status.OK,
                    "video/mp4",
                    new BoundedInputStream(fis, contentLength, bytesServed));
                
                if (DEBUG) Log.d(TAG, "Serving full content");
            }
            
            // Add headers for better streaming
            response.addHeader("Accept-Ranges", "bytes");
            response.addHeader("Content-Length", String.valueOf(contentLength));
            response.addHeader("Cache-Control", "public, max-age=31536000");
            response.addHeader("Connection", "keep-alive");
            response.addHeader("X-Content-Type-Options", "nosniff");
            response.addHeader("X-Frame-Options", "SAMEORIGIN");
            response.addHeader("Content-Disposition", "inline; filename=\"" + video.getName() + "\"");
            response.addHeader("Access-Control-Allow-Origin", "*");
            response.addHeader("Access-Control-Allow-Methods", "GET, HEAD");
            
            return response;
            
        } catch (IOException e) {
            Log.e(TAG, "Error serving video", e);
            if (fis != null) {
                try {
                    fis.close();
                } catch (IOException ignored) {}
            }
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT, "Internal Server Error");
        }
    }
    private static class BoundedInputStream extends FilterInputStream {
        private long remaining;
        private final AtomicLong bytesServed;
        private final long startTime;
        private long lastLogTime;
        private long totalBytesRead;
        
        public BoundedInputStream(InputStream in, long maxLength, AtomicLong bytesServed) {
            super(new BufferedInputStream(in, BUFFER_SIZE));
            this.remaining = maxLength;
            this.bytesServed = bytesServed;
            this.startTime = System.currentTimeMillis();
            this.lastLogTime = startTime;
            
            if (DEBUG) Log.d(TAG, String.format("Created BoundedInputStream: maxLength=%d", maxLength));
        }
        
        @Override
        public int read() throws IOException {
            if (remaining <= 0) return -1;
            
            int b = super.read();
            if (b != -1) {
                remaining--;
                totalBytesRead++;
                bytesServed.incrementAndGet();
                logProgressIfNeeded();
            }
            return b;
        }
        
        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) return -1;
            
            int maxRead = (int) Math.min(len, remaining);
            int bytesRead = super.read(b, off, maxRead);
            
            if (bytesRead > 0) {
                remaining -= bytesRead;
                totalBytesRead += bytesRead;
                bytesServed.addAndGet(bytesRead);
                logProgressIfNeeded();
            }
            
            return bytesRead;
        }
        
        private void logProgressIfNeeded() {
            if (DEBUG) {
                long now = System.currentTimeMillis();
                if (now - lastLogTime > 5000) {
                    double progress = 100.0 * totalBytesRead / (totalBytesRead + remaining);
                    double speed = totalBytesRead / ((now - startTime) / 1000.0) / 1024.0; // KB/s
                    Log.v(TAG, String.format("Streaming progress: %.1f%%, Speed: %.1f KB/s",
                        progress, speed));
                    lastLogTime = now;
                }
            }
        }
        
        @Override
        public void close() throws IOException {
            if (DEBUG) {
                long duration = System.currentTimeMillis() - startTime;
                double speed = totalBytesRead / (duration / 1000.0) / 1024.0; // KB/s
                Log.d(TAG, String.format("Completed streaming: %d bytes, %.1f KB/s",
                    totalBytesRead, speed));
            }
            super.close();
        }
    }
    
    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            if (DEBUG) Log.v(TAG, "Watchdog sleep interrupted");
        }
    }
    
    @Override
    public void stop() {
        if (DEBUG) {
            Log.i(TAG, "Stopping server. Final stats: " + getServerStats());
        }
        super.stop();
    }
}