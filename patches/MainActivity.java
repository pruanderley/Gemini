package com.gemini.google;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    private static final int FILE_CHOOSER_REQUEST = 4587;

    private WebView geminiWebView;
    private FrameLayout geminiContainer;
    private boolean geminiOpen = false;
    private ValueCallback<Uri[]> fileChooserCallback;

    @Override
    @SuppressLint("SetJavaScriptEnabled")
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Container sobreposto - cobre a tela toda quando aberto
        geminiContainer = new FrameLayout(this);
        geminiContainer.setVisibility(View.GONE);
        geminiContainer.setBackgroundColor(0xFF0f0f0f);

        // WebView nativo - nao tem restricoes de iframe
        geminiWebView = new WebView(this);
        WebSettings settings = geminiWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setUserAgentString(
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"
        );

        // Cookies
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(geminiWebView, true);

        // WebChromeClient com suporte a upload de arquivos.
        // Sem o onShowFileChooser, tocar em "Arquivos" no Gemini nao abre nada.
        geminiWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView,
                                             ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams fileChooserParams) {
                // Se ja havia uma escolha pendente, cancela ela primeiro
                if (fileChooserCallback != null) {
                    fileChooserCallback.onReceiveValue(null);
                }
                fileChooserCallback = filePathCallback;
                try {
                    Intent intent = fileChooserParams.createIntent();
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                } catch (Exception e) {
                    fileChooserCallback = null;
                    return false;
                }
                return true;
            }
        });

        geminiWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                // Tudo do Google fica no WebView - nao delega para app externo
                if (url.contains("google.com") ||
                    url.contains("googleapis.com") ||
                    url.contains("gstatic.com") ||
                    url.contains("accounts.google")) {
                    view.loadUrl(url);
                    return true;
                }
                // Outros links abrem no browser externo
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                } catch (Exception e) { e.printStackTrace(); }
                return true;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                // Fallback para Android antigo
                if (url.contains("google.com") ||
                    url.contains("googleapis.com") ||
                    url.contains("gstatic.com")) {
                    view.loadUrl(url);
                    return true;
                }
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(intent);
                } catch (Exception e) { e.printStackTrace(); }
                return true;
            }
        });

        // Adiciona o WebView no container
        geminiContainer.addView(geminiWebView,
            new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // Adiciona o container sobre o layout principal do Capacitor
        addContentView(geminiContainer,
            new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // Expoe bridge para o JS do index.html
        getBridge().getWebView().addJavascriptInterface(new GeminiBridge(), "Android");
    }

    // Recebe o arquivo escolhido e devolve para o WebView do Gemini
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQUEST) {
            if (fileChooserCallback != null) {
                Uri[] results = null;
                if (resultCode == Activity.RESULT_OK && data != null) {
                    if (data.getClipData() != null) {
                        // Varios arquivos selecionados
                        int count = data.getClipData().getItemCount();
                        results = new Uri[count];
                        for (int i = 0; i < count; i++) {
                            results[i] = data.getClipData().getItemAt(i).getUri();
                        }
                    } else if (data.getData() != null) {
                        // Um arquivo so
                        results = new Uri[] { data.getData() };
                    }
                }
                fileChooserCallback.onReceiveValue(results);
                fileChooserCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    public class GeminiBridge {

        @JavascriptInterface
        public void openGeminiWeb(String url) {
            runOnUiThread(() -> {
                if (url != null && !url.isEmpty()) {
                    geminiWebView.loadUrl(url);
                } else {
                    geminiWebView.loadUrl("https://gemini.google.com/app?hl=pt-BR");
                }
                geminiContainer.setVisibility(View.VISIBLE);
                geminiContainer.bringToFront();
                geminiOpen = true;
            });
        }

        @JavascriptInterface
        public void closeGeminiWeb() {
            runOnUiThread(() -> {
                geminiContainer.setVisibility(View.GONE);
                geminiOpen = false;
            });
        }

        @JavascriptInterface
        public void openUrl(String url) {
            runOnUiThread(() -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }

        @JavascriptInterface
        public void exitApp() {
            runOnUiThread(() -> {
                finishAffinity();
                System.exit(0);
            });
        }
    }

    @Override
    public void onBackPressed() {
        // Se Gemini Web esta aberto
        if (geminiOpen) {
            if (geminiWebView.canGoBack()) {
                geminiWebView.goBack();
            } else {
                geminiContainer.setVisibility(View.GONE);
                geminiOpen = false;
            }
            return;
        }
        // Comportamento padrao do app
        getBridge().getWebView().evaluateJavascript(
            "(function(){ return typeof window.handleBack==='function'" +
            "?window.handleBack():false; })()",
            value -> {
                if ("false".equals(value)) {
                    runOnUiThread(() -> {
                        finishAffinity();
                        System.exit(0);
                    });
                }
            });
    }

    @Override
    public void onPause() {
        super.onPause();
        // Grava os cookies em disco - mantem o login do Google
        // mesmo se o Android matar o app em segundo plano
        CookieManager.getInstance().flush();
    }

    @Override
    public void onDestroy() {
        if (geminiWebView != null) {
            geminiWebView.destroy();
        }
        super.onDestroy();
    }
}
