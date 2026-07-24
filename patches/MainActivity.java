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
import android.app.DownloadManager;
import android.content.Context;
import android.os.Environment;
import android.util.Base64;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    private static final int FILE_CHOOSER_REQUEST = 4587;

    private WebView geminiWebView;
    private FrameLayout geminiContainer;
    private boolean geminiOpen = false;
    private ValueCallback<Uri[]> fileChooserCallback;

    // Dominio base do site aberto no WebView (ex: "google.com", "claude.ai").
    // Navegacao dentro desse dominio fica no app; o resto abre no browser.
    private String allowedBaseDomain = "google.com";

    private static String baseDomainOf(String host) {
        if (host == null || host.isEmpty()) return "";
        String[] parts = host.split("\\.");
        if (parts.length < 2) return host;
        return parts[parts.length - 2] + "." + parts[parts.length - 1];
    }

    private boolean shouldStayInWebView(String url) {
        String host;
        try { host = Uri.parse(url).getHost(); } catch (Exception e) { return false; }
        if (host == null) return false;
        // dominio do site atualmente aberto
        if (!allowedBaseDomain.isEmpty() && host.endsWith(allowedBaseDomain)) return true;
        // dominios de apoio (login, imagens, scripts) do Google e da Anthropic
        return host.endsWith("google.com")
            || host.endsWith("googleapis.com")
            || host.endsWith("gstatic.com")
            || host.endsWith("googleusercontent.com")
            || host.endsWith("anthropic.com")
            || host.endsWith("claude.ai");
    }

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
                if (shouldStayInWebView(url)) {
                    view.loadUrl(url);
                    return true;
                }
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                } catch (Exception e) { e.printStackTrace(); }
                return true;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (shouldStayInWebView(url)) {
                    view.loadUrl(url);
                    return true;
                }
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(intent);
                } catch (Exception e) { e.printStackTrace(); }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // Injeta interceptor de downloads em CADA pagina carregada.
                // O DownloadListener do WebView NAO dispara pra blob: URLs,
                // entao capturamos no JS: escuta cliques em <a download>,
                // converte blob/data pra base64, e chama o bridge pra salvar.
                String js = "(function(){"
                    + "if(window.__dlInterceptor) return;"
                    + "window.__dlInterceptor=true;"
                    // Captura clique em <a download> na fase de captura (antes do site)
                    + "document.addEventListener('click',function(e){"
                    + "  var a=e.target.closest('a[download]');"
                    + "  if(!a) return;"
                    + "  var href=a.href||a.getAttribute('href')||'';"
                    + "  if(!href.startsWith('blob:')&&!href.startsWith('data:')) return;"
                    + "  e.preventDefault(); e.stopPropagation(); e.stopImmediatePropagation();"
                    + "  var fname=a.download||a.getAttribute('download')||'download';"
                    + "  if(href.startsWith('blob:')){"
                    + "    fetch(href).then(function(r){return r.blob();}).then(function(b){"
                    + "      var reader=new FileReader();"
                    + "      reader.onloadend=function(){"
                    + "        var b64=reader.result.split(',')[1]||'';"
                    + "        AppDownload.saveFile(b64,b.type||'application/octet-stream',fname);"
                    + "      };"
                    + "      reader.readAsDataURL(b);"
                    + "    }).catch(function(err){alert('Erro: '+err.message);});"
                    + "  } else if(href.startsWith('data:')){"
                    + "    var parts=href.split(',');"
                    + "    var mime=(parts[0].split(':')[1]||'').split(';')[0]||'application/octet-stream';"
                    + "    var b64=parts[1]||'';"
                    + "    AppDownload.saveFile(b64,mime,fname);"
                    + "  }"
                    + "},true);"
                    // Intercepta tambem cliques em botoes que disparam download programatico
                    // (ex: Claude cria <a> dinamicamente, clica nele, e remove)
                    + "var origClick=HTMLAnchorElement.prototype.click;"
                    + "HTMLAnchorElement.prototype.click=function(){"
                    + "  var href=this.href||'';"
                    + "  var dl=this.download||this.getAttribute('download');"
                    + "  if(dl!==null&&dl!==undefined&&(href.startsWith('blob:')||href.startsWith('data:'))){"
                    + "    var fname=dl||'download';"
                    + "    if(href.startsWith('blob:')){"
                    + "      var self=this;"
                    + "      fetch(href).then(function(r){return r.blob();}).then(function(b){"
                    + "        var reader=new FileReader();"
                    + "        reader.onloadend=function(){"
                    + "          var b64=reader.result.split(',')[1]||'';"
                    + "          AppDownload.saveFile(b64,b.type||'application/octet-stream',fname);"
                    + "        };"
                    + "        reader.readAsDataURL(b);"
                    + "      }).catch(function(err){alert('Erro: '+err.message);});"
                    + "    } else if(href.startsWith('data:')){"
                    + "      var parts=href.split(',');"
                    + "      var mime=(parts[0].split(':')[1]||'').split(';')[0]||'application/octet-stream';"
                    + "      AppDownload.saveFile(parts[1]||'',mime,fname);"
                    + "    }"
                    + "    return;"
                    + "  }"
                    + "  return origClick.apply(this,arguments);"
                    + "};"
                    + "})()";
                view.evaluateJavascript(js, null);
            }
        });

        // DownloadListener para URLs normais (https:) - o DownloadManager cuida
        geminiWebView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            if (url == null || url.startsWith("blob:") || url.startsWith("data:")) return;
            try {
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
                request.setMimeType(mimeType);
                request.addRequestHeader("User-Agent", userAgent);
                String cookies = CookieManager.getInstance().getCookie(url);
                if (cookies != null) request.addRequestHeader("Cookie", cookies);
                request.setTitle(fileName);
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
                DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                dm.enqueue(request);
                Toast.makeText(MainActivity.this, "Baixando: " + fileName, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                e.printStackTrace();
                Toast.makeText(MainActivity.this, "Erro ao iniciar download", Toast.LENGTH_SHORT).show();
            }
        });

        // Bridge JS para downloads de blob:/data: (chamado pelo interceptor injetado em onPageFinished)
        geminiWebView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void saveFile(String base64, String mimeType, String fileName) {
                try {
                    byte[] data = Base64.decode(base64, Base64.DEFAULT);
                    // Se o nome nao tem extensao, adiciona uma baseada no MIME
                    if (fileName != null && !fileName.contains(".")) {
                        String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
                        if (ext != null && !ext.isEmpty()) fileName = fileName + "." + ext;
                    }
                    if (fileName == null || fileName.isEmpty()) {
                        String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
                        fileName = "download_" + System.currentTimeMillis() + "." + (ext != null ? ext : "bin");
                    }
                    File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!dir.exists()) dir.mkdirs();
                    File file = new File(dir, fileName);
                    FileOutputStream fos = new FileOutputStream(file);
                    fos.write(data);
                    fos.close();
                    final String msg = "Salvo em Downloads: " + fileName;
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
                } catch (Exception e) {
                    e.printStackTrace();
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Erro ao salvar arquivo", Toast.LENGTH_SHORT).show());
                }
            }
        }, "AppDownload");

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
                String destino = (url != null && !url.isEmpty())
                    ? url
                    : "https://gemini.google.com/app?hl=pt-BR";
                try {
                    allowedBaseDomain = baseDomainOf(Uri.parse(destino).getHost());
                } catch (Exception e) {
                    allowedBaseDomain = "google.com";
                }
                geminiWebView.loadUrl(destino);
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