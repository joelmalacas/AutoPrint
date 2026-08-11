package com.example.autoprint;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;

public class TemplateComposer {

    // Resolução A4 Standard a 300 DPI
    private static final int A4_WIDTH = 2480;
    private static final int A4_HEIGHT = 3508;

    public static Bitmap processJournalTemplate(Context context, Bitmap photoBitmap, int templateResId) {
        Bitmap safePhoto = ensureSoftwareBitmap(photoBitmap);

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        Bitmap rawTemplate = BitmapFactory.decodeResource(context.getResources(), templateResId, options);

        if (rawTemplate == null) {
            return safePhoto;
        }

        Bitmap safeTemplate = ensureSoftwareBitmap(rawTemplate);

        // 1. Cria a folha A4 em branco (2480 x 3508)
        Bitmap a4PageBitmap = Bitmap.createBitmap(A4_WIDTH, A4_HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(a4PageBitmap);

        // 2. Desenha o template forçado a preencher os 2480x3508 da folha A4
        Rect srcTemplateRect = new Rect(0, 0, safeTemplate.getWidth(), safeTemplate.getHeight());
        Rect dstTemplateRect = new Rect(0, 0, A4_WIDTH, A4_HEIGHT);
        canvas.drawBitmap(safeTemplate, srcTemplateRect, dstTemplateRect, null);

        // 3. Coordenadas exatas para a foto dentro da moldura da folha A4
        float targetX = A4_WIDTH * 0.041f;       // ~101 px da esquerda
        float targetY = A4_HEIGHT * 0.170f;      // ~596 px do topo
        float targetWidth = A4_WIDTH * 0.575f;   // ~1426 px de largura
        float targetHeight = A4_HEIGHT * 0.402f;  // ~1410 px de altura

        RectF dstPhotoRect = new RectF(targetX, targetY, targetX + targetWidth, targetY + targetHeight);

        // 4. Recorta a foto tirada para o rácio exato da caixa
        Bitmap croppedPhoto = cropToAspectRatio(safePhoto, targetWidth / targetHeight);
        Rect srcPhotoRect = new Rect(0, 0, croppedPhoto.getWidth(), croppedPhoto.getHeight());

        // 5. Desenha a foto no jornal
        canvas.drawBitmap(croppedPhoto, srcPhotoRect, dstPhotoRect, null);

        return a4PageBitmap;
    }

    private static Bitmap ensureSoftwareBitmap(Bitmap bitmap) {
        if (bitmap != null && bitmap.getConfig() == Bitmap.Config.HARDWARE) {
            return bitmap.copy(Bitmap.Config.ARGB_8888, false);
        }
        return bitmap;
    }

    private static Bitmap cropToAspectRatio(Bitmap src, float targetRatio) {
        int srcWidth = src.getWidth();
        int srcHeight = src.getHeight();
        float srcRatio = (float) srcWidth / srcHeight;

        int cropWidth = srcWidth;
        int cropHeight = srcHeight;

        if (srcRatio > targetRatio) {
            cropWidth = Math.round(srcHeight * targetRatio);
        } else {
            cropHeight = Math.round(srcWidth / targetRatio);
        }

        int cropX = (srcWidth - cropWidth) / 2;
        int cropY = (srcHeight - cropHeight) / 2;

        return Bitmap.createBitmap(src, cropX, cropY, cropWidth, cropHeight);
    }
}