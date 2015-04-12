/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.webkit;

import android.app.ActivityManagerInternal;
import android.app.Application;
import android.app.AppGlobals;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.StrictMode;
import android.os.SystemProperties;
import android.os.Trace;
import android.text.TextUtils;
import android.util.AndroidRuntimeException;
import android.util.Log;
import com.android.server.LocalServices;
import dalvik.system.VMRuntime;

import java.io.File;
import java.util.Arrays;

import com.android.internal.os.Zygote;

/**
 * Top level factory, used creating all the main WebView implementation classes.
 *
 * @hide
 */
public final class WebViewFactory {

    private static final String CHROMIUM_WEBVIEW_FACTORY =
            "com.android.webview.chromium.WebViewChromiumFactoryProvider";

    private static final String NULL_WEBVIEW_FACTORY =
            "com.android.webview.nullwebview.NullWebViewFactoryProvider";

    private static final String LOGTAG = "WebViewFactory";

    private static final boolean DEBUG = false;

    // Cache the factory both for efficiency, and ensure any one process gets all webviews from the
    // same provider.
    private static WebViewFactoryProvider sProviderInstance;
    private static final Object sProviderLock = new Object();
    private static PackageInfo sPackageInfo;

    public static String getWebViewPackageName() {
        return AppGlobals.getInitialApplication().getString(
                com.android.internal.R.string.config_webViewPackageName);
    }

    public static PackageInfo getLoadedPackageInfo() {
        return sPackageInfo;
    }

    static WebViewFactoryProvider getProvider() {
        synchronized (sProviderLock) {
            // For now the main purpose of this function (and the factory abstraction) is to keep
            // us honest and minimize usage of WebView internals when binding the proxy.
            if (sProviderInstance != null) return sProviderInstance;

            Trace.traceBegin(Trace.TRACE_TAG_WEBVIEW, "WebViewFactory.getProvider()");
            try {
                Class<WebViewFactoryProvider> providerClass;
                Trace.traceBegin(Trace.TRACE_TAG_WEBVIEW, "WebViewFactory.getFactoryClass()");
                try {
                    providerClass = getFactoryClass();
                } catch (ClassNotFoundException e) {
                    Log.e(LOGTAG, "error loading provider", e);
                    throw new AndroidRuntimeException(e);
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_WEBVIEW);
                }

                StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskReads();
                Trace.traceBegin(Trace.TRACE_TAG_WEBVIEW, "providerClass.newInstance()");
                try {
                    sProviderInstance = providerClass.newInstance();
                    if (DEBUG) Log.v(LOGTAG, "Loaded provider: " + sProviderInstance);
                    return sProviderInstance;
                } catch (Exception e) {
                    Log.e(LOGTAG, "error instantiating provider", e);
                    throw new AndroidRuntimeException(e);
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_WEBVIEW);
                    StrictMode.setThreadPolicy(oldPolicy);
                }
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_WEBVIEW);
            }
        }
    }

    private static Class<WebViewFactoryProvider> getFactoryClass() throws ClassNotFoundException {
        Application initialApplication = AppGlobals.getInitialApplication();
        try {
            // First fetch the package info so we can log the webview package version.
            String packageName = getWebViewPackageName();
            sPackageInfo = initialApplication.getPackageManager().getPackageInfo(packageName, 0);
            Log.i(LOGTAG, "Loading " + packageName + " version " + sPackageInfo.versionName +
                          " (code " + sPackageInfo.versionCode + ")");

            // Construct a package context to load the Java code into the current app.
            Context webViewContext = initialApplication.createPackageContext(packageName,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            initialApplication.getAssets().addAssetPath(
                    webViewContext.getApplicationInfo().sourceDir);
            ClassLoader clazzLoader = webViewContext.getClassLoader();
            Trace.traceBegin(Trace.TRACE_TAG_WEBVIEW, "Class.forName()");
            try {
                return (Class<WebViewFactoryProvider>) Class.forName(CHROMIUM_WEBVIEW_FACTORY, true,
                                                                     clazzLoader);
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_WEBVIEW);
            }
        } catch (PackageManager.NameNotFoundException e) {
            // If the package doesn't exist, then try loading the null WebView instead.
            // If that succeeds, then this is a device without WebView support; if it fails then
            // swallow the failure, complain that the real WebView is missing and rethrow the
            // original exception.
            try {
                return (Class<WebViewFactoryProvider>) Class.forName(NULL_WEBVIEW_FACTORY);
            } catch (ClassNotFoundException e2) {
                // Ignore.
            }
            Log.e(LOGTAG, "Chromium WebView package does not exist", e);
            throw new AndroidRuntimeException(e);
        }
    }
}
