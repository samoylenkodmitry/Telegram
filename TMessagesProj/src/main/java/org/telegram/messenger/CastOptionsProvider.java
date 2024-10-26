package org.telegram.messenger;

import android.content.Context;

import com.google.android.gms.cast.CastMediaControlIntent;
import com.google.android.gms.cast.framework.CastOptions;
import com.google.android.gms.cast.framework.OptionsProvider;
import com.google.android.gms.cast.framework.SessionProvider;

import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class CastOptionsProvider implements OptionsProvider {
	
	@NonNull
	@Override
	public CastOptions getCastOptions(@NonNull final Context context) {
		return new CastOptions.Builder()
			.setReceiverApplicationId(
				CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID	
			).build();
	}
	
	@Nullable
	@Override
	public List<SessionProvider> getAdditionalSessionProviders(@NonNull final Context context) {
		return null;
	}
}
