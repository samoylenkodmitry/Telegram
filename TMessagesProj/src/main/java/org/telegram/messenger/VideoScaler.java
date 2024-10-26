package org.telegram.messenger;

import android.media.MediaMetadataRetriever;
import android.util.Log;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

public class VideoScaler {
	
	private static final int MAX_WIDTH = 320;//1280; //1920;  // Max width supported by Chromecast
	private static final int MAX_HEIGHT = 1080;//1024; //1080; // Max height supported by Chromecast
	
	// Method to scale video if needed
	public static File scaleVideoIfNecessary(File inputVideoFile, File outputFile) {
		// Retrieve original video dimensions
		int[] videoDimensions = getVideoDimensions(inputVideoFile);
		if (videoDimensions == null) {
			System.err.println("Failed to retrieve video dimensions.");
			return null;
		}
		int originalWidth = videoDimensions[0];
		int originalHeight = videoDimensions[1];
		
		// Check if scaling is necessary
		if (originalWidth <= MAX_WIDTH && originalHeight <= MAX_HEIGHT) {
			// No scaling needed, return original file
			return inputVideoFile;
		}
		
		// Calculate the scaling factor while preserving the aspect ratio
		float widthRatio = (float) MAX_WIDTH / originalWidth;
		float heightRatio = (float) MAX_HEIGHT / originalHeight;
		float scalingFactor = Math.min(widthRatio, heightRatio);
		
		// Compute the new dimensions
		int scaledWidth = Math.round(originalWidth * scalingFactor);
		int scaledHeight = Math.round(originalHeight * scalingFactor);
		
		// Scale the video using FFmpeg
		return scaleVideo(inputVideoFile, scaledWidth, scaledHeight, outputFile);
	}
	
	// Helper method to scale the video using FFmpeg
	private static File scaleVideo(File inputVideoFile, int scaledWidth, int scaledHeight, File outputFile) {
		String inputFilePath = inputVideoFile.getAbsolutePath();
		String outputFilePath = outputFile.getAbsolutePath();
		
		// Ensure the scaled height is even
		scaledHeight = (scaledHeight / 2) * 2;
		scaledWidth = (scaledWidth / 2) * 2;

		String scaleCommand = "-i \"" + inputFilePath + "\" -vf scale=" + scaledWidth + ":" + scaledHeight + " -c:v libx264 -crf 23 -preset medium -c:a copy -y \"" + outputFilePath + "\"";

		// Execute the FFmpeg command
		final FFmpegSession session = FFmpegKit.execute(scaleCommand);
		
		Log.d("VideoScaler", "FFmpeg scaling command: " + scaleCommand + ", session: " + session);
		if (ReturnCode.isSuccess(session.getReturnCode())) {
			// Return the output file if scaling was successful
			return outputFile;
		} else {
			// Handle the failure case
			return null;
		}
	}
	
	// Method to get the InputStream from a scaled video file
	public static InputStream getScaledVideoInputStream(File scaledVideoFile) {
		try {
			return new FileInputStream(scaledVideoFile);
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}
	
	// Helper method to retrieve the dimensions of the video
	private static int[] getVideoDimensions(File videoFile) {
		MediaMetadataRetriever retriever = new MediaMetadataRetriever();
		try {
			retriever.setDataSource(videoFile.getAbsolutePath());
			String widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
			String heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
			
			if (widthStr != null && heightStr != null) {
				int width = Integer.parseInt(widthStr);
				int height = Integer.parseInt(heightStr);
				return new int[]{width, height};
			}
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			try {
				retriever.release();
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
		return null;
	}
}