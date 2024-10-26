package org.telegram.messenger;

import android.content.Context;
import android.util.Log;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegKitConfig;
import com.arthenica.ffmpegkit.ReturnCode;

import fi.iki.elonen.NanoHTTPD;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

public class VideoStreamingService {
	
	private static SimpleHttpServer httpServer;
	private static Process ffmpegProcess;
	private static String pipePath;
	
	// Start the server and FFmpeg transcoding process
	public static void startServer(Context context, String uri) {
		try {
			// Create a new named pipe
			pipePath = FFmpegKitConfig.registerNewFFmpegPipe(context);
			if (pipePath == null) {
				Log.e("VideoStreamingService", "Failed to create named pipe.");
				return;
			}
			
			// Start FFmpeg to transcode and stream the video using the named pipe
			runFFmpeg(context, uri, pipePath);
			
			// Start the HTTP server once the pipe is ready
			httpServer = new SimpleHttpServer(8900, pipePath);
			httpServer.start();
			Log.d("VideoStreamingService", "Server started at: http://"+VideoServer.getCurrentLocalIp(context)+":8900");
			
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	// Stop the server and FFmpeg process
	public static void stopServer() {
		if (httpServer != null) {
			httpServer.stop();
			httpServer = null;
			Log.d("VideoStreamingService", "Server stopped.");
		}
		
		if (ffmpegProcess != null) {
			ffmpegProcess.destroy();
			ffmpegProcess = null;
			Log.d("VideoStreamingService", "FFmpeg process stopped.");
		}
		
		if (pipePath != null) {
			FFmpegKitConfig.closeFFmpegPipe(pipePath);
			pipePath = null;
			Log.d("VideoStreamingService", "Named pipe closed.");
		}
	}
	
	// Run FFmpeg using FFmpegKit and stream output to the named pipe
	private static void runFFmpeg(Context context, String uri, String pipePath) {
		// FFmpeg command to scale down video to fit within 720x400
		Log.d("VideoStreamingService", "Running FFmpeg command..., uri = `" + uri + "`, pipePath = `" + pipePath + "`");
		String ffmpegCommand = String.format(
			"-i \"%s\" -y -vf scale=\"'trunc(min(1,min(720/iw,400/ih))*iw/2)*2':'trunc(min(1,min(720/iw,400/ih))*ih/2)*2'\" -c:v libx264 -preset fast -c:a aac -f mp4 -movflags frag_keyframe+empty_moov %s",
			uri, pipePath
		);
		
		// Run FFmpeg command asynchronously using FFmpegKit
		FFmpegKit.executeAsync(ffmpegCommand, session -> {
			if (ReturnCode.isSuccess(session.getReturnCode())) {
				Log.d("VideoStreamingService", "FFmpeg command executed successfully.");
			} else {
				String error = session.getAllLogsAsString();
				Log.e("VideoStreamingService", "FFmpeg execution failed: " + error);
				stopServer();  // Stop the server on failure
			}
		});
	}
	
	// Simple HTTP server to serve the video stream from the named pipe
	private static class SimpleHttpServer extends NanoHTTPD {
		private final String pipePath;
		
		public SimpleHttpServer(int port, String pipePath) {
			super(port);
			this.pipePath = pipePath;
		}
		
		@Override
		public Response serve(IHTTPSession session) {
			Log.d("SimpleHttpServer", "Request: " + session.getUri());
			Log.d("SimpleHttpServer", "Method: " + session.getMethod());
			Log.d("SimpleHttpServer", "Headers: " + session.getHeaders());
			Log.d("SimpleHttpServer", "Params: " + session.getParms());
			Log.d("SimpleHttpServer", "Query: " + session.getQueryParameterString());
			Log.d("SimpleHttpServer", "Remote: " + session.getRemoteIpAddress());
			Log.d("SimpleHttpServer", "URI: " + session.getUri());
			// Serve the named pipe output as an MP4 stream
			try {
				FileInputStream fileInputStream = new FileInputStream(new File(pipePath));
				Log.d("SimpleHttpServer", "Serving video stream...");
				Log.d("SimpleHttpServer", "Content-Length: " + fileInputStream.available());
				Log.d("SimpleHttpServer", "File exists: " + new File(pipePath).exists());
				return newChunkedResponse(Response.Status.OK, "video/mp4", fileInputStream);
			} catch (IOException e) {
				Log.e("SimpleHttpServer", "Error reading from pipe: " + e.getMessage());
				e.printStackTrace();
				return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error reading from pipe");
			} finally {
				Log.d("SimpleHttpServer", "Request served.");
			}
		}
	}
}
