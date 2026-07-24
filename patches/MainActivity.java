package com.gemini.google;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
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
        if (!allowedBaseDomain.isEmpty() && host.endsWith(allowedBaseDomain)) return true;
        return host.endsWith("google.com")
            || host.endsWith("googleapis.com")
            || host.endsWith("gstatic.com")
            || host.endsWith("googleusercontent.com")
            || host.endsWith("anthropic.com")
            || host.endsWith("claude.ai");
    }

    // ------ SALVAR ARQUIVO NA PASTA DOWNLOADS ------
    // Usa MediaStore no Android 10+ (sem permissao necessaria).
    // Fallback direto pra versoes antigas.
    private void saveToDownloads(byte[] data, String mimeType, String fileName) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ : MediaStore (sem permissao)
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
                values.put(MediaStore.Downloads.IS_PENDING, 1);
                Uri uri = getContentResolver().insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    OutputStream os = getContentResolver().openOutputStream(uri);
                    if (os != null) {
                        os.write(data);
                        os.close();
                    }
                    values.clear();
                    values.put(MediaStore.Downloads.IS_PENDING, 0);
                    getContentResolver().update(uri, values, null, null);
                }
            } else {
                // Android 9 e anterior
                File dir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) dir.mkdirs();
                FileOutputStream fos = new FileOutputStream(new File(dir, fileName));
                fos.write(data);
                fos.close();
            }
            final String msg = "Salvo em Downloads: " + fileName;
            runOnUiThread(() ->
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
        } catch (Exception e) {
            e.printStackTrace();
            runOnUiThread(() ->
                Toast.makeText(this, "Erro ao salvar arquivo", Toast.LENGTH_SHORT).show());
        }
    }

    // ------ JS DO INTERCEPTOR DE DOWNLOADS ------
    // Injetado em onPageFinished. Captura TUDO: cliques em <a download>,
    // chamadas programaticas a .click(), e window.open com blob:.
    private static final String DOWNLOAD_INTERCEPTOR_JS = "(function(){"
        + "if(window.__dlOK) return; window.__dlOK=true;"
        // Rastreia cada blob criado pra poder ler depois (mesmo se o site revogar a URL)
        + "var B={};"
        + "var _cou=URL.createObjectURL;"
        + "URL.createObjectURL=function(o){"
        + "  var u=_cou.call(URL,o); if(o instanceof Blob) B[u]=o; return u;"
        + "};"
        + "var _rou=URL.revokeObjectURL;"
        + "URL.revokeObjectURL=function(u){ delete B[u]; _rou.call(URL,u); };"
        // Funcao central que salva o blob/data via bridge
        + "function dl(href,fname){"
        + "  if(!href) return false;"
        + "  if(href.startsWith('blob:')){"
        + "    var blob=B[href];"
        + "    if(blob){"
        + "      var r=new FileReader();"
        + "      r.onloadend=function(){"
        + "        var b64=r.result.split(',')[1]||'';"
        + "        AppDL.save(b64, blob.type||'application/octet-stream', fname||'download');"
        + "      }; r.readAsDataURL(blob);"
        + "    } else {"
        + "      fetch(href).then(function(r){return r.blob();}).then(function(b){"
        + "        var r2=new FileReader();"
        + "        r2.onloadend=function(){"
        + "          var b64=r2.result.split(',')[1]||'';"
        + "          AppDL.save(b64, b.type||'application/octet-stream', fname||'download');"
        + "        }; r2.readAsDataURL(b);"
        + "      }).catch(function(e){AppDL.save('','error',fname||'download');});"
        + "    }"
        + "    return true;"
        + "  }"
        + "  if(href.startsWith('data:')){"
        + "    var p=href.split(','); var m=(p[0].split(':')[1]||'').split(';')[0];"
        + "    AppDL.save(p[1]||'', m||'application/octet-stream', fname||'download');"
        + "    return true;"
        + "  }"
        + "  return false;"
        + "}"
        // 1) Captura cliques em links com atributo download
        + "document.addEventListener('click',function(e){"
        + "  var a=e.target.closest('a[download]');"
        + "  if(!a) return;"
        + "  var href=a.href||a.getAttribute('href')||'';"
        + "  if(dl(href, a.download||a.getAttribute('download'))){"
        + "    e.preventDefault(); e.stopPropagation(); e.stopImmediatePropagation();"
        + "  }"
        + "},true);"
        // 2) Intercepta .click() programatico (Claude cria <a>, chama .click(), e remove)
        + "var _ac=HTMLAnchorElement.prototype.click;"
        + "HTMLAnchorElement.prototype.click=function(){"
        + "  var href=this.href||'';"
        + "  var dattr=this.download; if(dattr===undefined) dattr=this.getAttribute('download');"
        + "  if(dattr!==null && dattr!==undefined && dl(href, dattr)) return;"
        + "  return _ac.apply(this,arguments);"
        + "};"
        // 3) Intercepta window.open com blob: (DeepSeek usa isso)
        + "var _wo=window.open;"
        + "window.open=function(u){"
        + "  if(u && typeof u==='string' && u.startsWith('blob:')){"
        + "    if(dl(u,'download')) return null;"
        + "  }"
        + "  return _wo.apply(window,arguments);"
        + "};"
        + "})()";

    @Override
    @SuppressLint("SetJavaScriptEnabled")
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Container sobreposto - cobre a tela toda quando aberto
        geminiContainer = new FrameLayout(this);
        geminiContainer.setVisibility(View.GONE);
        geminiContainer.setBackgroundColor(0xFF0f0f0f);

        // WebView nativo
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
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/124.0.0.0 Mobile Safari/537.36"
        );

        // Cookies
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(geminiWebView, true);

        // Upload de arquivos
        geminiWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView,
                                             ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams fileChooserParams) {
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

        // Navegacao + injecao do interceptor de downloads
        geminiWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (shouldStayInWebView(url)) { view.loadUrl(url); return true; }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception e) { e.printStackTrace(); }
                return true;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (shouldStayInWebView(url)) { view.loadUrl(url); return true; }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception e) { e.printStackTrace(); }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // Injeta o interceptor a cada pagina carregada
                view.evaluateJavascript(DOWNLOAD_INTERCEPTOR_JS, null);
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                super.doUpdateVisitedHistory(view, url, isReload);
                // Reinjeta nas navegacoes SPA (Claude, DeepSeek sao SPAs)
                view.evaluateJavascript(DOWNLOAD_INTERCEPTOR_JS, null);
            }
        });

        // DownloadListener: captura apenas downloads de URLs normais (https:).
        // Downloads de blob:/data: sao tratados pelo interceptor JS acima.
        geminiWebView.setDownloadListener((url, userAgent, contentDisposition, mimeType, len) -> {
            if (url == null) return;
            // blob: e data: ja sao tratados pelo JS injetado - ignora aqui
            if (url.startsWith("blob:") || url.startsWith("data:")) return;
            try {
                DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
                req.setMimeType(mimeType);
                req.addRequestHeader("User-Agent", userAgent);
                String cookies = CookieManager.getInstance().getCookie(url);
                if (cookies != null) req.addRequestHeader("Cookie", cookies);
                req.setTitle(fileName);
                req.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                req.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS, fileName);
                DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                dm.enqueue(req);
                Toast.makeText(this, "Baixando: " + fileName, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        // Bridge de download - recebe base64 do JS interceptor e salva
        geminiWebView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void save(String base64, String mimeType, String fileName) {
                if ("error".equals(mimeType)) {
                    runOnUiThread(() ->
                        Toast.makeText(MainActivity.this,
                            "Nao foi possivel capturar o arquivo", Toast.LENGTH_SHORT).show());
                    return;
                }
                try {
                    byte[] data = Base64.decode(base64, Base64.DEFAULT);
                    // Adiciona extensao se nao tiver
                    if (fileName != null && !fileName.contains(".")) {
                        String ext = MimeTypeMap.getSingleton()
                            .getExtensionFromMimeType(mimeType);
                        if (ext != null && !ext.isEmpty()) fileName = fileName + "." + ext;
                    }
                    if (fileName == null || fileName.isEmpty()) {
                        String ext = MimeTypeMap.getSingleton()
                            .getExtensionFromMimeType(mimeType);
                        fileName = "download_" + System.currentTimeMillis()
                            + "." + (ext != null ? ext : "bin");
                    }
                    saveToDownloads(data, mimeType, fileName);
                } catch (Exception e) {
                    e.printStackTrace();
                    runOnUiThread(() ->
                        Toast.makeText(MainActivity.this,
                            "Erro ao decodificar arquivo", Toast.LENGTH_SHORT).show());
                }
            }
        }, "AppDL");

        // Adiciona o WebView no container
        geminiContainer.addView(geminiWebView,
            new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // Container sobre o layout do Capacitor
        addContentView(geminiContainer,
            new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // Bridge do index.html
        getBridge().getWebView().addJavascriptInterface(new GeminiBridge(), "Android");
    }

    // Recebe arquivo escolhido no seletor
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQUEST) {
            if (fileChooserCallback != null) {
                Uri[] results = null;
                if (resultCode == Activity.RESULT_OK && data != null) {
                    if (data.getClipData() != null) {
                        int count = data.getClipData().getItemCount();
                        results = new Uri[count];
                        for (int i = 0; i < count; i++) {
                            results[i] = data.getClipData().getItemAt(i).getUri();
                        }
                    } else if (data.getData() != null) {
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
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception e) { e.printStackTrace(); }
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
        if (geminiOpen) {
            if (geminiWebView.canGoBack()) {
                geminiWebView.goBack();
            } else {
                geminiContainer.setVisibility(View.GONE);
                geminiOpen = false;
            }
            return;
        }
        getBridge().getWebView().evaluateJavascript(
            "(function(){ return typeof window.handleBack==='function'"
            + "?window.handleBack():false; })()",
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
        CookieManager.getInstance().flush();
    }

    @Override
    public void onDestroy() {
        if (geminiWebView != null) geminiWebView.destroy();
        super.onDestroy();
    }
}
