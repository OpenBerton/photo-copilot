package com.yrf3.photocopilot.reference;

import android.net.Uri;

/** A recommendation ID, backend image URL and accessible description. */
public final class ReferencePhoto {
    public final String id;
    public final Uri image;
    public final String description;

    public ReferencePhoto(String id, Uri image, String description) {
        this.id = id;
        this.image = image;
        this.description = description;
    }
}
