package com.yrf3.photocopilot.reference;

import android.net.Uri;

/** A stable selection ID and a local image URI, independent of the picker layout. */
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
