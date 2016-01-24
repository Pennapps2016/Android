package com.tristanwiley.laserqr;

import com.google.android.gms.vision.MultiProcessor;
import com.google.android.gms.vision.Tracker;
import com.google.android.gms.vision.barcode.Barcode;
import com.tristanwiley.laserqr.camera.GraphicOverlay;

/**
 * Open source from Google
 */
public class QRTrackerFactory implements MultiProcessor.Factory<Barcode>{
    private GraphicOverlay<BarcodeGraphic> mGraphicOverlay;

    QRTrackerFactory(GraphicOverlay<BarcodeGraphic> barcodeGraphicOverlay) {
        mGraphicOverlay = barcodeGraphicOverlay;
    }

    @Override
    public Tracker<Barcode> create(Barcode barcode) {
        BarcodeGraphic graphic = new BarcodeGraphic(mGraphicOverlay);
        return new BarcodeGraphicTracker(mGraphicOverlay, graphic);
    }

}
