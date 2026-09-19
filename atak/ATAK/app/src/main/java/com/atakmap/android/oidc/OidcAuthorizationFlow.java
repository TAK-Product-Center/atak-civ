package com.atakmap.android.oidc;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.atakmap.android.oidc.core.AuthorizationAttempt;
import com.atakmap.android.oidc.core.BearerSelector;
import com.atakmap.android.oidc.core.Jwt;
import com.atakmap.android.oidc.core.OidcError;
import com.atakmap.android.oidc.core.OidcException;
import com.atakmap.android.oidc.core.RedirectReceiver;
import com.atakmap.android.oidc.core.RedirectReceivers;
import com.atakmap.android.oidc.core.RedirectResponse;
import com.atakmap.android.oidc.core.TakOidcConfig;
import com.atakmap.android.oidc.core.TokenRequest;
import com.atakmap.android.oidc.core.TokenResponse;
import com.atakmap.comms.SslNetCotPort;
import com.atakmap.comms.http.TakHttpClient;
import com.atakmap.comms.http.TakHttpResponse;
import com.atakmap.coremap.log.Log;
import com.atakmap.net.CertificateManager;

import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.entity.StringEntity;
import org.apache.http.client.params.ClientPNames;
import org.apache.http.client.methods.HttpRequestBase;

import java.io.IOException;
import java.net.URI;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLException;

/**
 * Runs the OIDC authorization-code flow used to bootstrap certificate
 * enrollment. Listener callbacks are made on the background {@code oidc-flow}
 * thread, including {@link Listener#onAuthorized(String, String)}.
 */
public class OidcAuthorizationFlow {
    private static final String TAG = "OidcAuthorizationFlow";
    private static final long REDIRECT_TIMEOUT_MILLIS = 5L * 60L * 1000L;
    private static final String DISCOVERY_PATH =
            "/login/.well-known/openid-configuration";
    private static final String TLS_CONFIG_PATH = "/api/tls/config";
    private static final Pattern IDENTITY_ELEMENT = Pattern.compile(
            "<identity\\b[^>]*>(.*?)</identity\\s*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern IDENTITY_ATTRIBUTE = Pattern.compile(
            "\\bidentity\\s*=\\s*(['\"])(.*?)\\1",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    public interface Listener {
        void onAuthorized(String bearerAssertion, String identity);

        void onFailed(OidcError error, String safeDetail);
    }

    private final Context context;
    private final String takHost;
    /**
     * Quick connect (the connection dialog's "use trust" box unchecked):
     * public CAs plus hostname verification, exactly the trust the existing
     * enrollment and profile paths use for the same request. Otherwise the
     * tactical policy: only the trust configured for this server, no hostname
     * check (CertificateManager "allowAllHostnames"). F07.
     */
    private final boolean quickConnect;
    private boolean finished;
    private final Handler mainHandler;
    private final Object stateLock = new Object();

    private volatile RedirectReceiver receiver;
    private volatile boolean cancelled;
    private Thread flowThread;
    private AuthorizationAttempt attempt;
    private TokenResponse tokens;
    private String assertion;

    public OidcAuthorizationFlow(Context context, String takHost,
            boolean quickConnect) {
        if (context == null)
            throw new IllegalArgumentException("context is required");
        if (takHost == null || takHost.trim().isEmpty())
            throw new IllegalArgumentException("TAK host is required");

        Context applicationContext = context.getApplicationContext();
        this.context = applicationContext == null ? context
                : applicationContext;
        this.takHost = takHost;
        this.quickConnect = quickConnect;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void start(final Listener listener) {
        if (listener == null)
            throw new IllegalArgumentException("listener is required");

        final Thread thread;
        synchronized (stateLock) {
            if (flowThread != null)
                throw new IllegalStateException(
                        "OIDC authorization is already running");
            cancelled = false;
            finished = false;
            thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    runFlow(listener);
                }
            }, "oidc-flow");
            flowThread = thread;
        }
        thread.start();
    }

    public void cancel() {
        final RedirectReceiver current;
        synchronized (stateLock) {
            if (cancelled)
                return;
            cancelled = true;
            current = receiver;
        }
        if (current != null)
            current.cancel();
    }

    private void runFlow(Listener listener) {
        TakHttpClient takClient = null;
        try {
            checkCancelled();
            String takOrigin = "https://" + takHost + ":"
                    + SslNetCotPort.getServerApiPort(
                            SslNetCotPort.Type.CERT_ENROLLMENT);
            String enrollmentBaseUrl = "https://" + takHost
                    + SslNetCotPort.getServerApiPath(
                            SslNetCotPort.Type.CERT_ENROLLMENT);
            takClient = newHttpClient(enrollmentBaseUrl,
                    !quickConnect, OidcError.TLS_FAILURE);

            TakOidcConfig config = discover(takClient,
                    takOrigin + DISCOVERY_PATH);
            checkCancelled();

            RedirectReceiver currentReceiver = RedirectReceivers
                    .forMode(config.getRedirectMode());
            receiver = currentReceiver;
            // T12 / v02 (Silas L4): the state is bound before the receiver is
            // published, so there is no window in which a stray or hostile
            // callback can fill the mailbox. The receiver rejects everything
            // while unbound.
            final String state = AuthorizationAttempt.newState();
            currentReceiver.bindState(state);
            currentReceiver.start();
            checkCancelled();

            attempt = AuthorizationAttempt.create(config.getAndroidClientId(),
                    currentReceiver.getRedirectUri(), state);
            String authorizationUrl = attempt.buildAuthorizationUrl(
                    config.getAuthorizationEndpoint(), config.getScopes());
            launchBrowser(authorizationUrl);
            checkCancelled();

            String redirectUri = currentReceiver
                    .awaitRedirect(REDIRECT_TIMEOUT_MILLIS);
            checkCancelled();
            String code = RedirectResponse.extractCode(redirectUri,
                    attempt.getState());

            tokens = exchangeToken(config, code, attempt);
            checkCancelled();
            Jwt.checkNonce(tokens.getIdToken(), attempt.getNonce());
            assertion = BearerSelector.select(tokens);
            tokens = null;
            attempt = null;

            String identity = fetchIdentity(takClient, assertion);
            // F09: success and cancellation are arbitrated under the lock so a
            // cancel that lands here cannot be followed by onAuthorized.
            if (!finish()) {
                throw new OidcException(OidcError.BROWSER_CANCELLED,
                        "authorization cancelled");
            }
            listener.onAuthorized(assertion, identity);
        } catch (OidcException e) {
            finish();
            logStep("failed", e.getError().name());
            listener.onFailed(e.getError(), safeDetail(e));
        } catch (RuntimeException e) {
            finish();
            OidcError error = isTlsFailure(e) ? OidcError.TLS_FAILURE
                    : OidcError.SERVER_REJECTED;
            logStep("failed", error.name());
            listener.onFailed(error, "unexpected flow error");
        } finally {
            try {
                RedirectReceiver current = receiver;
                if (current != null)
                    current.close();
            } finally {
                try {
                    if (takClient != null)
                        takClient.shutdown();
                } finally {
                    synchronized (stateLock) {
                        receiver = null;
                        attempt = null;
                        tokens = null;
                        assertion = null;
                        flowThread = null;
                    }
                }
            }
        }
    }

    /** Marks the flow finished; false when it was cancelled first. */
    private boolean finish() {
        synchronized (stateLock) {
            if (cancelled || finished)
                return false;
            finished = true;
            return true;
        }
    }

    /**
     * F05: the Apache client follows redirects and copies explicit headers
     * (the bearer) onto the redirected request. Every sensitive request is
     * pinned to its origin: any 3xx is an error, never followed.
     */
    private static void noRedirects(HttpRequestBase request) {
        request.getParams().setBooleanParameter(
                ClientPNames.HANDLE_REDIRECTS, false);
    }

    private TakOidcConfig discover(TakHttpClient client, String url)
            throws OidcException {
        HttpGet request = new HttpGet(url);
        noRedirects(request);
        request.setHeader("Accept", "application/json");
        TakHttpResponse response = null;
        try {
            response = client.execute(request, false);
            int status = response.getStatusCode();
            logStep("discovery", String.valueOf(status));
            if (!isSuccessful(status))
                throw new OidcException(OidcError.DISCOVERY_FAILED,
                        "HTTP " + status);
            return TakOidcConfig.parse(response.getStringEntity());
        } catch (IOException e) {
            throw networkFailure(OidcError.DISCOVERY_FAILED,
                    "discovery request failed", e);
        } finally {
            if (response != null)
                response.close();
        }
    }

    private TokenResponse exchangeToken(TakOidcConfig config, String code,
            AuthorizationAttempt currentAttempt) throws OidcException {
        TakHttpClient client = null;
        TakHttpResponse response = null;
        try {
            String tokenEndpoint = config.getTokenEndpoint();
            URI endpoint = URI.create(tokenEndpoint);
            if (endpoint.getRawAuthority() == null)
                throw new OidcException(OidcError.IDP_UNREACHABLE,
                        "invalid token endpoint");
            String idpBaseUrl = endpoint.getScheme() + "://"
                    + endpoint.getRawAuthority();
            // T17: the quick connect "allow all hostnames" relaxation applies to
            // the TAK Server only; the IdP host is always hostname verified.
            client = newHttpClient(idpBaseUrl, false, OidcError.TLS_FAILURE);

            HttpPost request = new HttpPost(tokenEndpoint);
            noRedirects(request);
            request.setHeader("Accept", "application/json");
            request.setHeader("Content-Type",
                    "application/x-www-form-urlencoded");
            request.setEntity(new StringEntity(TokenRequest.formBody(code,
                    currentAttempt.getRedirectUri(),
                    currentAttempt.getClientId(),
                    currentAttempt.getCodeVerifier()), "UTF-8"));

            response = client.execute(request, false);
            int status = response.getStatusCode();
            logStep("token", String.valueOf(status));
            if (status == 400 || status == 401)
                throw new OidcException(OidcError.TOKEN_INVALID,
                        "HTTP " + status);
            if (!isSuccessful(status))
                throw new OidcException(OidcError.IDP_UNREACHABLE,
                        "HTTP " + status);
            return TokenResponse.parse(response.getStringEntity());
        } catch (IOException e) {
            throw networkFailure(OidcError.IDP_UNREACHABLE,
                    "token request failed", e);
        } catch (IllegalArgumentException e) {
            throw new OidcException(OidcError.IDP_UNREACHABLE,
                    "invalid token endpoint", e);
        } finally {
            if (response != null)
                response.close();
            if (client != null)
                client.shutdown();
        }
    }

    private String fetchIdentity(TakHttpClient client, String bearerAssertion)
            throws OidcException {
        HttpGet request = new HttpGet(client.getUrl(TLS_CONFIG_PATH));
        noRedirects(request);
        request.setHeader("Authorization", "Bearer " + bearerAssertion);
        request.setHeader("Accept", "application/xml");
        TakHttpResponse response = null;
        try {
            response = client.execute(request, false);
            int status = response.getStatusCode();
            logStep("tls config", String.valueOf(status));
            if (status == 403)
                throw new OidcException(OidcError.GROUP_DENIED,
                        "HTTP " + status);
            if (status == 401)
                throw new OidcException(OidcError.TOKEN_INVALID,
                        "HTTP " + status);
            if (!isSuccessful(status))
                throw new OidcException(OidcError.SERVER_REJECTED,
                        "HTTP " + status);
            return extractIdentity(response.getStringEntity());
        } catch (IOException e) {
            throw networkFailure(OidcError.SERVER_REJECTED,
                    "tls config request failed", e);
        } finally {
            if (response != null)
                response.close();
        }
    }

    private TakHttpClient newHttpClient(String baseUrl,
            boolean relaxHostnames, OidcError error) throws OidcException {
        SSLSocketFactory socketFactory = CertificateManager.getSockFactory(
                false, baseUrl, relaxHostnames);
        if (socketFactory == null)
            throw new OidcException(error, "TLS socket factory unavailable");
        return new TakHttpClient(baseUrl, socketFactory);
    }

    private void launchBrowser(final String authorizationUrl)
            throws OidcException {
        final CountDownLatch launched = new CountDownLatch(1);
        final AtomicReference<OidcException> failure =
                new AtomicReference<>();
        boolean posted = mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (cancelled) {
                        failure.set(new OidcException(
                                OidcError.BROWSER_CANCELLED,
                                "authorization cancelled"));
                    } else {
                        CustomTabLauncher.launch(context, authorizationUrl);
                    }
                } catch (OidcException e) {
                    failure.set(e);
                } catch (RuntimeException e) {
                    failure.set(new OidcException(
                            OidcError.BROWSER_CANCELLED,
                            "browser launch failed"));
                } finally {
                    launched.countDown();
                }
            }
        });
        if (!posted)
            throw new OidcException(OidcError.BROWSER_CANCELLED,
                    "browser launch failed");

        try {
            if (!launched.await(30, TimeUnit.SECONDS))
                throw new OidcException(OidcError.BROWSER_CANCELLED,
                        "browser launch timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OidcException(OidcError.BROWSER_CANCELLED,
                    "browser launch interrupted");
        }
        if (failure.get() != null)
            throw failure.get();
    }

    private void checkCancelled() throws OidcException {
        if (cancelled)
            throw new OidcException(OidcError.BROWSER_CANCELLED,
                    "authorization cancelled");
    }

    private static String extractIdentity(String document)
            throws OidcException {
        String value = matchIdentity(IDENTITY_ELEMENT, document, 1);
        if (value == null)
            value = matchIdentity(IDENTITY_ATTRIBUTE, document, 2);
        if (value == null)
            throw new OidcException(OidcError.SERVER_REJECTED,
                    "no identity in tls config");

        value = decodeXmlEntities(value).trim();
        if (value.isEmpty())
            throw new OidcException(OidcError.SERVER_REJECTED,
                    "no identity in tls config");
        return value;
    }

    private static String matchIdentity(Pattern pattern, String document,
            int group) {
        if (document == null)
            return null;
        Matcher matcher = pattern.matcher(document);
        return matcher.find() ? matcher.group(group) : null;
    }

    private static String decodeXmlEntities(String value) {
        return value.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&");
    }

    private static boolean isSuccessful(int status) {
        return status >= 200 && status < 300;
    }

    private static OidcException networkFailure(OidcError defaultError,
            String detail, IOException cause) {
        OidcError error = isTlsFailure(cause) ? OidcError.TLS_FAILURE
                : defaultError;
        return new OidcException(error, detail, cause);
    }

    private static boolean isTlsFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SSLException
                    || current instanceof CertificateException
                    || current instanceof CertPathValidatorException)
                return true;
            current = current.getCause();
        }
        return false;
    }

    private static String safeDetail(OidcException exception) {
        String detail = exception.getMessage();
        return detail == null || detail.isEmpty()
                ? exception.getError().name()
                : detail;
    }

    private static void logStep(String name, String status) {
        // This log contains no token, code, verifier, state or nonce.
        Log.d(TAG, "step " + name + ": " + status);
    }
}
