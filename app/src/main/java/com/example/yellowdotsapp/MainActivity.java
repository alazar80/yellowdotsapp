package com.example.yellowdotsapp;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import org.opencv.android.OpenCVLoader;

import java.io.IOException;

import yellowdots.YellowDotDecoder;

public class MainActivity extends AppCompatActivity {

    private TextView resultView;
    private Button pickBtn;

    private final ActivityResultLauncher<String> pickFile =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) handleUri(uri);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        resultView = findViewById(R.id.resultText);
        pickBtn = findViewById(R.id.pickButton);

        if (!OpenCVLoader.initDebug()) {
            Toast.makeText(this, "OpenCV failed to init", Toast.LENGTH_LONG).show();
        }

        pickBtn.setOnClickListener(v -> pickFile.launch("*/*"));
    }

    private void handleUri(Uri uri) {
        Bitmap pageBitmap = null;

        try {
            if ("application/pdf".equals(getContentResolver().getType(uri))) {
                pageBitmap = renderFirstPageFromPdf(uri, 600);
            } else {
                pageBitmap = MediaStore.Images.Media.getBitmap(getContentResolver(), uri);
            }
        } catch (Exception e) {
            Toast.makeText(this, "Failed to open file: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }

        if (pageBitmap == null) {
            Toast.makeText(this, "No bitmap from file.", Toast.LENGTH_LONG).show();
            return;
        }

        YellowDotDecoder decoder = new YellowDotDecoder();
        YellowDotDecoder.DecodeOutput out = decoder.process(pageBitmap);
        resultView.setText(out.toString());
    }

    private Bitmap renderFirstPageFromPdf(Uri uri, int dpi) throws IOException {
        ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r");
        if (pfd == null) return null;
        PdfRenderer renderer = new PdfRenderer(pfd);
        PdfRenderer.Page page = renderer.openPage(0);

        float widthInches = page.getWidth() / 72.0f;
        float heightInches = page.getHeight() / 72.0f;
        int widthPx = Math.max(1, Math.round(widthInches * dpi));
        int heightPx = Math.max(1, Math.round(heightInches * dpi));

        Bitmap bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888);
        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
        page.close();
        renderer.close();
        pfd.close();
        return bmp;
    }
}
