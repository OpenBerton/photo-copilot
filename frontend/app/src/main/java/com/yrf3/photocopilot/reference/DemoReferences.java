package com.yrf3.photocopilot.reference;

import android.content.Context;
import android.net.Uri;

import com.yrf3.photocopilot.R;

import java.util.Arrays;
import java.util.List;

/** Bundled demonstration photos, not scene analysis or recommendation results. */
public final class DemoReferences {
    private DemoReferences() { }

    public static List<ReferencePhoto> load(Context context) {
        return Arrays.asList(
                photo(context, "waterfront", R.drawable.reference_waterfront, R.string.reference_waterfront),
                photo(context, "street", R.drawable.reference_street, R.string.reference_street),
                photo(context, "coast", R.drawable.reference_coast, R.string.reference_coast),
                photo(context, "mountains", R.drawable.reference_mountains, R.string.reference_mountains));
    }

    private static ReferencePhoto photo(Context context, String id, int image, int description) {
        return new ReferencePhoto(id, Uri.parse("android.resource://" + context.getPackageName() + "/" + image),
                context.getString(description));
    }
}
