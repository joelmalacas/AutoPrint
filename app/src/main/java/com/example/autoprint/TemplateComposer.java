package com.example.autoprint;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;

public class TemplateComposer {

    /**
     * Junta a foto tirada ao template
     *
     * @param context Contexto da aplicação.
     * @param photoBitmap A foto tirada pela câmara.
     * @param templateResId O ID do recurso do jornal (ex: R.drawable.template).
     * @return Bitmap final pronto a enviar para o PrinterHelper.
     */
    public static Bitmap processJournalTemplate(Context context, Bitmap photoBitmap, int templateResId) {
        // 1. Carrega o template do jornal mantendo a qualidade original
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        Bitmap templateBitmap = BitmapFactory.decodeResource(context.getResources(), templateResId, options);

        if (templateBitmap == null) {
            return photoBitmap;
        }

        int tWidth = templateBitmap.getWidth();
        int tHeight = templateBitmap.getHeight();

        // 2. Cria o bitmap final com as dimensões exatas do jornal
        Bitmap resultBitmap = Bitmap.createBitmap(tWidth, tHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(resultBitmap);

        // 3. Desenha o fundo do jornal
        canvas.drawBitmap(templateBitmap, 0, 0, null);

        // 4. Calcula a caixa da foto em pixeis exatos com base nas percentagens do layout
        float targetX = tWidth * 0.038f;
        float targetY = tHeight * 0.168f;
        float targetWidth = tWidth * 0.582f;
        float targetHeight = tHeight * 0.412f;

        RectF dstRect = new RectF(targetX, targetY, targetX + targetWidth, targetY + targetHeight);

        // 5. Ajusta a foto tirada (Center Crop) para preencher o retângulo sem esticar/deformar
        Bitmap croppedPhoto = cropToAspectRatio(photoBitmap, targetWidth / targetHeight);

        Rect srcRect = new Rect(0, 0, croppedPhoto.getWidth(), croppedPhoto.getHeight());

        // 6. Desenha a foto recortada dentro da caixa reservada do jornal
        canvas.drawBitmap(croppedPhoto, srcRect, dstRect, null);

        return resultBitmap;
    }

    /**
     * Corta o centro da imagem para corresponder à proporção exata do destino,
     * evitando que as pessoas/elementos fiquem esticados.
     */
    private static Bitmap cropToAspectRatio(Bitmap src, float targetRatio) {
        int srcWidth = src.getWidth();
        int srcHeight = src.getHeight();
        float srcRatio = (float) srcWidth / srcHeight;

        int cropWidth = srcWidth;
        int cropHeight = srcHeight;

        if (srcRatio > targetRatio) {
            // A imagem original é mais larga -> corta as laterais
            cropWidth = Math.round(srcHeight * targetRatio);
        } else {
            // A imagem original é mais alta -> corta o topo e fundo
            cropHeight = Math.round(srcWidth / targetRatio);
        }

        int cropX = (srcWidth - cropWidth) / 2;
        int cropY = (srcHeight - cropHeight) / 2;

        return Bitmap.createBitmap(src, cropX, cropY, cropWidth, cropHeight);
    }
}