package com.example.autoprint;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

public class AutoPrintClickService extends AccessibilityService {

    private static final String TAG = "AutoPrintDebug";

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getPackageName() == null) return;

        String pkg = event.getPackageName().toString().toLowerCase();

        // Verifica se a janela aberta pertence ao PrintSpooler do sistema Android
        if (pkg.contains("print") || pkg.contains("spooler")) {
            AccessibilityNodeInfo rootNode = getRootInActiveWindow();
            if (rootNode != null) {
                boolean clicked = tryClickPrintButton(rootNode);
                if (clicked) {
                    Log.d(TAG, "Acessibilidade: Botão de impressão clicado com sucesso!");
                }
                rootNode.recycle();
            }
        }
    }

    private boolean tryClickPrintButton(AccessibilityNodeInfo node) {
        if (node == null) return false;

        // 1. Procura por IDs de botões conhecidos (Google, Samsung, Xiaomi)
        String resId = node.getViewIdResourceName();
        if (resId != null) {
            String idLower = resId.toLowerCase();
            if (idLower.contains("print_button") || idLower.contains("button_print") || idLower.contains("confirm")) {
                if (performClick(node)) return true;
            }
        }

        // 2. Procura por texto ou descrição contendo "Imprimir" ou "Print"
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();

        String label = "";
        if (text != null) label += text.toString();
        if (desc != null) label += " " + desc.toString();
        label = label.toLowerCase();

        if (label.contains("imprimir") || label.contains("print")) {
            if (performClick(node)) return true;
        }

        // 3. Procura recursivamente nos nós filhos
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                boolean success = tryClickPrintButton(child);
                child.recycle();
                if (success) return true;
            }
        }

        return false;
    }

    private boolean performClick(AccessibilityNodeInfo node) {
        if (node.isClickable()) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        // Se o elemento pai for o clicável (ex: ImageView dentro de um FrameLayout)
        AccessibilityNodeInfo parent = node.getParent();
        if (parent != null) {
            boolean parentClicked = parent.isClickable() && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            parent.recycle();
            return parentClicked;
        }
        return false;
    }

    @Override
    public void onInterrupt() {}
}