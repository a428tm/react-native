/**
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.react.devsupport;

import android.content.Context;
import android.os.Handler;
import android.text.TextUtils;

import com.facebook.common.logging.FLog;
import com.facebook.infer.annotation.Assertions;
import com.facebook.react.bridge.UiThreadUtil;
import com.facebook.react.common.ReactConstants;
import com.facebook.react.modules.systeminfo.AndroidInfoHelpers;
import com.squareup.okhttp.Call;
import com.squareup.okhttp.Callback;
import com.squareup.okhttp.ConnectionPool;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import com.squareup.okhttp.ResponseBody;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import okio.Okio;
import okio.Sink;

/**
 * Helper class for all things about the debug server running in the engineer's host machine.
 *
 * One can use 'debug_http_host' shared preferences key to provide a host name for the debug server.
 * If the setting is empty we support and detect two basic configuration that works well for android
 * emulators connectiong to debug server running on emulator's host:
 *  - Android stock emulator with standard non-configurable local loopback alias: 10.0.2.2,
 *  - Genymotion emulator with default settings: 10.0.3.2
 */
public class DevServerHelper {

  public static final String RELOAD_APP_EXTRA_JS_PROXY = "jsproxy";
  private static final String RELOAD_APP_ACTION_SUFFIX = ".RELOAD_APP_ACTION";

  private static final String BUNDLE_URL_FORMAT =
      "http://%s/%s.bundle?platform=android&dev=%s&hot=%s&minify=%s";
  private static final String SOURCE_MAP_URL_FORMAT =
      BUNDLE_URL_FORMAT.replaceFirst("\\.bundle", ".map");
  private static final String LAUNCH_JS_DEVTOOLS_COMMAND_URL_FORMAT =
      "http://%s/launch-js-devtools";
  private static final String ONCHANGE_ENDPOINT_URL_FORMAT =
      "http://%s/onchange";
  private static final String WEBSOCKET_PROXY_URL_FORMAT = "ws://%s/debugger-proxy?role=client";
  private static final String PACKAGER_STATUS_URL_FORMAT = "http://%s/status";

  private static final String PACKAGER_OK_STATUS = "packager-status:running";

  private static final int LONG_POLL_KEEP_ALIVE_DURATION_MS = 2 * 60 * 1000; // 2 mins
  private static final int LONG_POLL_FAILURE_DELAY_MS = 5000;
  private static final int HTTP_CONNECT_TIMEOUT_MS = 5000;

  public interface BundleDownloadCallback {
    void onSuccess();
    void onFailure(Exception cause);
  }

  public interface OnServerContentChangeListener {
    void onServerContentChanged();
  }

  public interface PackagerStatusCallback {
    void onPackagerStatusFetched(boolean packagerIsRunning);
  }

  private final DevInternalSettings mSettings;
  private final OkHttpClient mClient;
  private final Handler mRestartOnChangePollingHandler;

  private boolean mOnChangePollingEnabled;
  private @Nullable OkHttpClient mOnChangePollingClient;
  private @Nullable OnServerContentChangeListener mOnServerContentChangeListener;
  private @Nullable Call mDownloadBundleFromURLCall;

  public DevServerHelper(DevInternalSettings settings) {
    mSettings = settings;
    mClient = new OkHttpClient();
    mClient.setConnectTimeout(HTTP_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

    // No read or write timeouts by default
    mClient.setReadTimeout(0, TimeUnit.MILLISECONDS);
    mClient.setWriteTimeout(0, TimeUnit.MILLISECONDS);
    mRestartOnChangePollingHandler = new Handler();
  }

  /** Intent action for reloading the JS */
  public static String getReloadAppAction(Context context) {
    return context.getPackageName() + RELOAD_APP_ACTION_SUFFIX;
  }

  public String getWebsocketProxyURL() {
    return String.format(Locale.US, WEBSOCKET_PROXY_URL_FORMAT, getDebugServerHost());
  }

  /**
   * @return the host to use when connecting to the bundle server from the host itself.
   */
  private static String getHostForJSProxy() {
    return AndroidInfoHelpers.DEVICE_LOCALHOST;
  }

  /**
   * @return whether we should enable dev mode when requesting JS bundles.
   */
  private boolean getDevMode() {
    return mSettings.isJSDevModeEnabled();
  }

  /**
   * @return whether we should request minified JS bundles.
   */
  private boolean getJSMinifyMode() {
    return mSettings.isJSMinifyEnabled();
  }

  /**
   * @return whether we should enabled HMR when requesting JS bundles.
   */
  private boolean getHMR() {
    return mSettings.isHotModuleReplacementEnabled();
  }

  /**
   * @return the host to use when connecting to the bundle server.
   */
  private String getDebugServerHost() {
    // Check debug server host setting first. If empty try to detect emulator type and use default
    // hostname for those
    String hostFromSettings = mSettings.getDebugServerHost();

    if (!TextUtils.isEmpty(hostFromSettings)) {
      return Assertions.assertNotNull(hostFromSettings);
    }

    String host = AndroidInfoHelpers.getServerHost();

    if (host.equals(AndroidInfoHelpers.DEVICE_LOCALHOST)) {
      FLog.w(
        ReactConstants.TAG,
        "You seem to be running on device. Run 'adb reverse tcp:8081 tcp:8081' " +
          "to forward the debug server's port to the device.");
    }

    return host;
  }

  private static String createBundleURL(String host, String jsModulePath, boolean devMode, boolean hmr, boolean jsMinify) {
    return String.format(Locale.US, BUNDLE_URL_FORMAT, host, jsModulePath, devMode, hmr, jsMinify);
  }

  public void downloadBundleFromURL(
      final BundleDownloadCallback callback,
      final String jsModulePath,
      final File outputFile) {
    final String bundleURL = createBundleURL(getDebugServerHost(), jsModulePath, getDevMode(), getHMR(), getJSMinifyMode());
    final Request request = new Request.Builder()
        .url(bundleURL)
        .build();
    mDownloadBundleFromURLCall = Assertions.assertNotNull(mClient.newCall(request));
    mDownloadBundleFromURLCall.enqueue(new Callback() {
      @Override
      public void onFailure(Request request, IOException e) {
        // ignore callback if call was cancelled
        if (mDownloadBundleFromURLCall == null || mDownloadBundleFromURLCall.isCanceled()) {
          mDownloadBundleFromURLCall = null;
          return;
        }
        mDownloadBundleFromURLCall = null;

        StringBuilder sb = new StringBuilder();
        sb.append("Could not connect to development server.\n\n")
          .append("Try the following to fix the issue:\n")
          .append("\u2022 Ensure that the packager server is running\n")
          .append("\u2022 Ensure that your device/emulator is connected to your machine and has USB debugging enabled - run 'adb devices' to see a list of connected devices\n")
          .append("\u2022 If you're on a physical device connected to the same machine, run 'adb reverse tcp:8081 tcp:8081' to forward requests from your device\n")
          .append("\u2022 If your device is on the same Wi-Fi network, set 'Debug server host & port for device' in 'Dev settings' to your machine's IP address and the port of the local dev server - e.g. 10.0.1.1:8081\n\n")
          .append("URL: ").append(request.urlString());
        callback.onFailure(new DebugServerException(sb.toString()));
      }

      @Override
      public void onResponse(Response response) throws IOException {
        // ignore callback if call was cancelled
        if (mDownloadBundleFromURLCall == null || mDownloadBundleFromURLCall.isCanceled()) {
          mDownloadBundleFromURLCall = null;
          return;
        }
        mDownloadBundleFromURLCall = null;

        // Check for server errors. If the server error has the expected form, fail with more info.
        if (!response.isSuccessful()) {
          String body = response.body().string();
          DebugServerException debugServerException = DebugServerException.parse(body);
          if (debugServerException != null) {
            callback.onFailure(debugServerException);
          } else {
            StringBuilder sb = new StringBuilder();
            sb.append("The development server returned response error code: ").append(response.code()).append("\n\n")
              .append("URL: ").append(request.urlString()).append("\n\n")
              .append("Body:\n")
              .append(body);
            callback.onFailure(new DebugServerException(sb.toString()));
          }
          return;
        }

        Sink output = null;
        try {
          output = Okio.sink(outputFile);
          Okio.buffer(response.body().source()).readAll(output);
          callback.onSuccess();
        } finally {
          if (output != null) {
            output.close();
          }
        }
      }
    });
  }

  public void cancelDownloadBundleFromURL() {
    if (mDownloadBundleFromURLCall != null) {
      mDownloadBundleFromURLCall.cancel();
      mDownloadBundleFromURLCall = null;
    }
  }

  public void isPackagerRunning(final PackagerStatusCallback callback) {
    String statusURL = createPackagerStatusURL(getDebugServerHost());
    Request request = new Request.Builder()
        .url(statusURL)
        .build();

    mClient.newCall(request).enqueue(
        new Callback() {
          @Override
          public void onFailure(Request request, IOException e) {
            FLog.w(
                ReactConstants.TAG,
                "The packager does not seem to be running as we got an IOException requesting " +
                    "its status: " + e.getMessage());
            callback.onPackagerStatusFetched(false);
          }

          @Override
          public void onResponse(Response response) throws IOException {
            if (!response.isSuccessful()) {
              FLog.e(
                  ReactConstants.TAG,
                  "Got non-success http code from packager when requesting status: " +
                      response.code());
              callback.onPackagerStatusFetched(false);
              return;
            }
            ResponseBody body = response.body();
            if (body == null) {
              FLog.e(
                  ReactConstants.TAG,
                  "Got null body response from packager when requesting status");
              callback.onPackagerStatusFetched(false);
              return;
            }
            if (!PACKAGER_OK_STATUS.equals(body.string())) {
              FLog.e(
                  ReactConstants.TAG,
                  "Got unexpected response from packager when requesting status: " + body.string());
              callback.onPackagerStatusFetched(false);
              return;
            }
            callback.onPackagerStatusFetched(true);
          }
        });
  }

  private static String createPackagerStatusURL(String host) {
    return String.format(Locale.US, PACKAGER_STATUS_URL_FORMAT, host);
  }

  public void stopPollingOnChangeEndpoint() {
    mOnChangePollingEnabled = false;
    mRestartOnChangePollingHandler.removeCallbacksAndMessages(null);
    if (mOnChangePollingClient != null) {
      mOnChangePollingClient.cancel(this);
      mOnChangePollingClient = null;
    }
    mOnServerContentChangeListener = null;
  }

  public void startPollingOnChangeEndpoint(
      OnServerContentChangeListener onServerContentChangeListener) {
    if (mOnChangePollingEnabled) {
      // polling already enabled
      return;
    }
    mOnChangePollingEnabled = true;
    mOnServerContentChangeListener = onServerContentChangeListener;
    mOnChangePollingClient = new OkHttpClient();
    mOnChangePollingClient
        .setConnectionPool(new ConnectionPool(1, LONG_POLL_KEEP_ALIVE_DURATION_MS))
        .setConnectTimeout(HTTP_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    enqueueOnChangeEndpointLongPolling();
  }

  private void handleOnChangePollingResponse(boolean didServerContentChanged) {
    if (mOnChangePollingEnabled) {
      if (didServerContentChanged) {
        UiThreadUtil.runOnUiThread(new Runnable() {
          @Override
          public void run() {
            if (mOnServerContentChangeListener != null) {
              mOnServerContentChangeListener.onServerContentChanged();
            }
          }
        });
      }
      enqueueOnChangeEndpointLongPolling();
    }
  }

  private void enqueueOnChangeEndpointLongPolling() {
    Request request = new Request.Builder().url(createOnChangeEndpointUrl()).tag(this).build();
    Assertions.assertNotNull(mOnChangePollingClient).newCall(request).enqueue(new Callback() {
      @Override
      public void onFailure(Request request, IOException e) {
        if (mOnChangePollingEnabled) {
          // this runnable is used by onchange endpoint poller to delay subsequent requests in case
          // of a failure, so that we don't flood network queue with frequent requests in case when
          // dev server is down
          FLog.d(ReactConstants.TAG, "Error while requesting /onchange endpoint", e);
          mRestartOnChangePollingHandler.postDelayed(
              new Runnable() {
            @Override
            public void run() {
              handleOnChangePollingResponse(false);
            }
          },
              LONG_POLL_FAILURE_DELAY_MS);
        }
      }

      @Override
      public void onResponse(Response response) throws IOException {
        handleOnChangePollingResponse(response.code() == 205);
      }
    });
  }

  private String createOnChangeEndpointUrl() {
    return String.format(Locale.US, ONCHANGE_ENDPOINT_URL_FORMAT, getDebugServerHost());
  }

  private String createLaunchJSDevtoolsCommandUrl() {
    return String.format(LAUNCH_JS_DEVTOOLS_COMMAND_URL_FORMAT, getDebugServerHost());
  }

  public void launchJSDevtools() {
    Request request = new Request.Builder()
        .url(createLaunchJSDevtoolsCommandUrl())
        .build();
    mClient.newCall(request).enqueue(new Callback() {
      @Override
      public void onFailure(Request request, IOException e) {
        // ignore HTTP call response, this is just to open a debugger page and there is no reason
        // to report failures from here
      }

      @Override
      public void onResponse(Response response) throws IOException {
        // ignore HTTP call response - see above
      }
    });
  }

  public String getSourceMapUrl(String mainModuleName) {
    return String.format(Locale.US, SOURCE_MAP_URL_FORMAT, getDebugServerHost(), mainModuleName, getDevMode(), getHMR(), getJSMinifyMode());
  }

  public String getSourceUrl(String mainModuleName) {
    return String.format(Locale.US, BUNDLE_URL_FORMAT, getDebugServerHost(), mainModuleName, getDevMode(), getHMR(), getJSMinifyMode());
  }

  public String getJSBundleURLForRemoteDebugging(String mainModuleName) {
    // The host IP we use when connecting to the JS bundle server from the emulator is not the
    // same as the one needed to connect to the same server from the JavaScript proxy running on the
    // host itself.
    return createBundleURL(getHostForJSProxy(), mainModuleName, getDevMode(), getHMR(), getJSMinifyMode());
  }
}
