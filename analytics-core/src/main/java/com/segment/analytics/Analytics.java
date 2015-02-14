/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2014 Segment, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.segment.analytics;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import com.segment.analytics.internal.Utils;
import com.segment.analytics.internal.model.payloads.AliasPayload;
import com.segment.analytics.internal.model.payloads.BasePayload;
import com.segment.analytics.internal.model.payloads.GroupPayload;
import com.segment.analytics.internal.model.payloads.IdentifyPayload;
import com.segment.analytics.internal.model.payloads.ScreenPayload;
import com.segment.analytics.internal.model.payloads.TrackPayload;
import java.util.Collections;
import java.util.Map;

import static com.segment.analytics.IntegrationManager.ActivityLifecyclePayload;
import static com.segment.analytics.IntegrationManager.ActivityLifecyclePayload.Type;
import static com.segment.analytics.internal.Utils.OWNER_INTEGRATION_MANAGER;
import static com.segment.analytics.internal.Utils.OWNER_MAIN;
import static com.segment.analytics.internal.Utils.VERB_CREATE;
import static com.segment.analytics.internal.Utils.VERB_SKIP;
import static com.segment.analytics.internal.Utils.debug;
import static com.segment.analytics.internal.Utils.getResourceString;
import static com.segment.analytics.internal.Utils.hasPermission;
import static com.segment.analytics.internal.Utils.isNullOrEmpty;

/**
 * The entry point into the Segment for Android SDK.
 * <p/>
 * The idea is simple: one pipeline for all your data. Segment is the single hub to collect,
 * translate and route your data with the flip of a switch.
 * <p/>
 * Analytics for Android will automatically batch events, queue them to disk, and upload it
 * periodically to Segment for you. It will also look up your project's settings (that you've
 * configured in the web interface), specifically looking up settings for bundled integrations, and
 * then initialize them for you on the user's phone, and mapping our standardized events to formats
 * they can all understand. You only need to instrument Segment once, then flip a switch to install
 * new tools.
 * <p/>
 * This class is the main entry point into the client API. Use {@link
 * #with(android.content.Context)} for the global singleton instance or construct your own instance
 * with {@link Builder}.
 *
 * @see <a href="https://Segment/">Segment</a>
 */
public class Analytics {
  public static final Handler HANDLER = new Handler(Looper.getMainLooper()) {
    @Override public void handleMessage(Message msg) {
      switch (msg.what) {
        default:
          throw new AssertionError("Unknown handler message received: " + msg.what);
      }
    }
  };
  static final String WRITE_KEY_RESOURCE_IDENTIFIER = "analytics_write_key";
  private static final Properties EMPTY_PROPERTIES = new Properties();
  static Analytics singleton = null;

  final Application application;
  final IntegrationManager integrationManager;
  final SegmentDispatcher segmentDispatcher;
  final Stats stats;
  final Traits.Cache traitsCache;
  final AnalyticsContext analyticsContext;
  final Options defaultOptions;
  final LogLevel logLevel;
  boolean shutdown;

  /**
   * Return a reference to the global default {@link Analytics} instance.
   * <p/>
   * This instance is automatically initialized with defaults that are suitable to most
   * implementations.
   * <p/>
   * If these settings do not meet the requirements of your application, you can override defaults
   * in {@code analytics.xml}, or you can construct your own instance with full control over the
   * configuration by using {@link Builder}.
   * <p/>
   * By default, events are uploaded every 30 seconds, or every 20 events (whichever occurs first),
   * and debugging is disabled.
   */
  public static Analytics with(Context context) {
    if (singleton == null) {
      if (context == null) {
        throw new IllegalArgumentException("Context must not be null.");
      }
      synchronized (Analytics.class) {
        if (singleton == null) {
          String writeKey = getResourceString(context, WRITE_KEY_RESOURCE_IDENTIFIER);
          Builder builder = new Builder(context, writeKey);

          try {
            String packageName = context.getPackageName();
            int flags = context.getPackageManager().getApplicationInfo(packageName, 0).flags;
            boolean debugging = (flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
            if (debugging) {
              builder.logLevel(LogLevel.INFO);
            }
          } catch (PackageManager.NameNotFoundException ignored) {
          }

          singleton = builder.build();
        }
      }
    }
    return singleton;
  }

  /**
   * Set the global instance returned from {@link #with}.
   * <p/>
   * This method must be called before any calls to {@link #with} and may only be called once.
   *
   * @since 2.3
   */
  public static void setSingletonInstance(Analytics analytics) {
    synchronized (Analytics.class) {
      if (singleton != null) {
        throw new IllegalStateException("Singleton instance already exists.");
      }
      singleton = analytics;
    }
  }

  Analytics(Application application, IntegrationManager integrationManager,
      SegmentDispatcher segmentDispatcher, Stats stats, Traits.Cache traitsCache,
      AnalyticsContext analyticsContext, Options defaultOptions, LogLevel logLevel) {
    this.application = application;
    this.integrationManager = integrationManager;
    this.segmentDispatcher = segmentDispatcher;
    this.stats = stats;
    this.traitsCache = traitsCache;
    this.analyticsContext = analyticsContext;
    this.defaultOptions = defaultOptions;
    this.logLevel = logLevel;

    if (integrationManager != null) {
      application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
        @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
          submit(new ActivityLifecyclePayload(Type.CREATED, activity, savedInstanceState));
        }

        @Override public void onActivityStarted(Activity activity) {
          submit(new ActivityLifecyclePayload(Type.STARTED, activity, null));
        }

        @Override public void onActivityResumed(Activity activity) {
          submit(new ActivityLifecyclePayload(Type.RESUMED, activity, null));
        }

        @Override public void onActivityPaused(Activity activity) {
          submit(new ActivityLifecyclePayload(Type.PAUSED, activity, null));
        }

        @Override public void onActivityStopped(Activity activity) {
          submit(new ActivityLifecyclePayload(Type.STOPPED, activity, null));
        }

        @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
          submit(new ActivityLifecyclePayload(Type.SAVE_INSTANCE, activity, outState));
        }

        @Override public void onActivityDestroyed(Activity activity) {
          submit(new ActivityLifecyclePayload(Type.DESTROYED, activity, null));
        }
      });
    }
  }

  /**
   * Identify lets you tie one of your users and their actions to a recognizable {@code userId}. It
   * also lets you record {@code traits} about the user, like their email, name, account type, etc.
   *
   * @see #identify(String, Traits, Options)
   */
  public String identify(String userId) {
    return identify(userId, null, null);
  }

  /**
   * Identify lets you tie one of your users and their actions to a recognizable {@code userId}. It
   * also lets you record {@code traits} about the user, like their email, name, account type, etc.
   *
   * @see #identify(String, Traits, Options)
   */
  public String identify(Traits traits) {
    return identify(null, traits, null);
  }

  /**
   * Identify lets you tie one of your users and their actions to a recognizable {@code userId}. It
   * also lets you record {@code traits} about the user, like their email, name, account type, etc.
   * <p/>
   * Traits and userId will be automatically cached and available on future sessions for the same
   * user. To update a trait on the server, simply call identify with the same user id (or null).
   * You can also use {@link #identify(Traits)} for this purpose.
   *
   * @param userId Unique identifier which you recognize a user by in your own database. If this
   * is null or empty, any previous id we have (could be the anonymous id) will be
   * used.
   * @param newTraits Traits about the user
   * @param options To configure the call
   * @return The previous ID assigned to the user. Use it to call {@link #alias(String, Options)}
   * @throws IllegalArgumentException if userId is null or an empty string
   * @see <a href="https://segment.com/docs/tracking-api/identify/">Identify Documentation</a>
   */
  public String identify(String userId, Traits newTraits, Options options) {
    String previousId = traitsCache.get().userIdOrAnonymousId();
    if (!isNullOrEmpty(userId)) {
      traitsCache.get().putUserId(userId);
    }
    if (options == null) {
      options = defaultOptions;
    }
    if (!isNullOrEmpty(newTraits)) {
      Traits traits = traitsCache.get();
      traits.putAll(newTraits);
      traitsCache.set(traits);
      analyticsContext.setTraits(traits);
    }

    BasePayload payload = new IdentifyPayload(analyticsContext, options, traitsCache.get());
    submit(payload);
    return previousId;
  }

  /**
   * The group method lets you associate a user with a group. It also lets you record custom traits
   * about the group, like industry or number of employees.
   *
   * @see #group(String, Traits, Options)
   */
  public void group(String groupId) {
    group(groupId, null, null);
  }

  /**
   * The group method lets you associate a user with a group. It also lets you record custom traits
   * about the group, like industry or number of employees.
   * <p/>
   * If you've called {@link #identify(String, Traits, Options)} before, this will automatically
   * remember the userId. If not, it will fall back to use the anonymousId instead.
   *
   * @param groupId Unique identifier which you recognize a group by in your own database. Must not
   * be null or empty.
   * @param options To configure the call
   * @throws IllegalArgumentException if groupId is null or an empty string
   * @see <a href="https://segment.com/docs/tracking-api/group/">Group Documentation</a>
   */
  public void group(String groupId, Traits groupTraits, Options options) {
    if (isNullOrEmpty(groupId)) {
      throw new IllegalArgumentException("groupId must not be null or empty.");
    }
    if (groupTraits == null) {
      groupTraits = new Traits();
    }
    if (options == null) {
      options = defaultOptions;
    }

    BasePayload payload = new GroupPayload(analyticsContext, options, groupId, groupTraits);
    submit(payload);
  }

  /**
   * The track method is how you record any actions your users perform. Each action is known by a
   * name, like 'Purchased a T-Shirt'. You can also record properties specific to those actions.
   * For example a 'Purchased a Shirt' event might have properties like revenue or size.
   *
   * @see #track(String, Properties, Options)
   */
  public void track(String event) {
    track(event, null, null);
  }

  /**
   * The track method is how you record any actions your users perform. Each action is known by a
   * name, like 'Purchased a T-Shirt'. You can also record properties specific to those actions.
   * For example a 'Purchased a Shirt' event might have properties like revenue or size.
   *
   * @see #track(String, Properties, Options)
   */
  public void track(String event, Properties properties) {
    track(event, properties, null);
  }

  /**
   * The track method is how you record any actions your users perform. Each action is known by a
   * name, like 'Purchased a T-Shirt'. You can also record properties specific to those actions.
   * For example a 'Purchased a Shirt' event might have properties like revenue or size.
   *
   * @param event Name of the event. Must not be null or empty.
   * @param properties {@link Properties} to add extra information to this call
   * @param options To configure the call
   * @throws IllegalArgumentException if event name is null or an empty string
   * @see <a href="https://segment.com/docs/tracking-api/track/">Track Documentation</a>
   */
  public void track(String event, Properties properties, Options options) {
    if (isNullOrEmpty(event)) {
      throw new IllegalArgumentException("event must not be null or empty.");
    }
    if (properties == null) {
      properties = EMPTY_PROPERTIES;
    }
    if (options == null) {
      options = defaultOptions;
    }

    BasePayload payload = new TrackPayload(analyticsContext, options, event, properties);
    submit(payload);
  }

  /** @see #screen(String, String, Properties, Options) */
  public void screen(String category, String name) {
    screen(category, name, null, null);
  }

  /** @see #screen(String, String, Properties, Options) */
  public void screen(String category, String name, Properties properties) {
    screen(category, name, properties, null);
  }

  /**
   * The screen methods let your record whenever a user sees a screen of your mobile app, and
   * attach
   * a name, category or properties to the screen.
   * <p/>
   * Either category or name must be provided.
   *
   * @param category A category to describe the screen
   * @param name A name for the screen
   * @param properties {@link Properties} to add extra information to this call
   * @param options To configure the call
   * @see <a href="http://segment.com/docs/tracking-api/page-and-screen/">Screen Documentation</a>
   */
  public void screen(String category, String name, Properties properties, Options options) {
    if (isNullOrEmpty(category) && isNullOrEmpty(name)) {
      throw new IllegalArgumentException("either category or name must be provided.");
    }

    if (properties == null) {
      properties = EMPTY_PROPERTIES;
    }
    if (options == null) {
      options = defaultOptions;
    }

    BasePayload payload = new ScreenPayload(analyticsContext, options, category, name, properties);
    submit(payload);
  }

  /**
   * The alias method is used to merge two user identities, effectively connecting two sets of user
   * data as one. This is an advanced method, but it is required to manage user identities
   * successfully in some of our integrations.
   *
   * @see #alias(String, Options)
   */
  public void alias(String previousId) {
    alias(previousId, null);
  }

  /**
   * The alias method is used to merge two user identities, effectively connecting two sets of user
   * data as one. This is an advanced method, but it is required to manage user identities
   * successfully in some of our integrations.
   * <p>
   *
   * Usage:
   * <pre> <code>
   *   String previousId = analytics.identify(newId);
   *   analytics.alias(previousId, options);
   * </code> </pre>
   *
   * @param previousId The previous ID for the user that you want to alias from, that you
   * previously called identify with as their user ID, or their anonymous ID. It can be retrieved
   * by calling {@link #identify(String, Traits, Options)} (String)}
   * @param options To configure the call
   * @throws IllegalArgumentException if newId is null or empty
   * @see <a href="https://segment.com/docs/tracking-api/alias/">Alias Documentation</a>
   */
  public void alias(String previousId, Options options) {
    if (isNullOrEmpty(previousId)) {
      throw new IllegalArgumentException("previousId must not be null or empty.");
    }
    if (isNullOrEmpty(traitsCache.get().userId())) {
      throw new IllegalStateException("user must be identified with a userId before aliasing.");
    }
    if (options == null) {
      options = defaultOptions;
    }

    BasePayload payload = new AliasPayload(analyticsContext, options, previousId);
    submit(payload);
  }

  /**
   * Asynchronously flushes all messages in the queue to the server, and tells bundled integrations
   * to do the same.
   * <p>
   * Note that this will do nothing for bundled integrations that don't provide an explicit flush
   * method.
   */
  public void flush() {
    segmentDispatcher.dispatchFlush(0);
    if (integrationManager != null) integrationManager.dispatchFlush();
  }

  /** Get the {@link AnalyticsContext} used by this instance. */
  @SuppressWarnings("UnusedDeclaration") public AnalyticsContext getAnalyticsContext() {
    return analyticsContext;
  }

  /** Creates a {@link StatsSnapshot} of the current stats for this instance. */
  public StatsSnapshot getSnapshot() {
    return stats.createSnapshot();
  }

  /** Logs out the current user by clearing any information, including traits and user id. */
  public void logout() {
    traitsCache.delete();
    traitsCache.set(Traits.create());
    analyticsContext.setTraits(traitsCache.get());
  }

  /** Stops this instance from accepting further requests. */
  public void shutdown() {
    if (this == singleton) {
      throw new UnsupportedOperationException("Default singleton instance cannot be shutdown.");
    }
    if (shutdown) {
      return;
    }
    if (integrationManager != null) {
      integrationManager.shutdown();
    }
    segmentDispatcher.shutdown();
    stats.shutdown();
    shutdown = true;
  }

  /**
   * Register to be notified when a bundled integration is ready.
   * <p/>
   * In most cases, integrations would have already been initialized, and the callback will be
   * invoked fairly quickly. However there may be a latency the first time the app is launched, and
   * we don't have settings for bundled integrations yet. This is compounded if the user is offline
   * on the first run.
   * <p/>
   * You can only register for one callback per integration at a time, and passing in a {@code
   * callback} will remove the previous callback for that integration.
   * </p>
   * The callback is invoked on the same thread we call integrations on, so if you want to update
   * the UI, make sure you move off to the main thread.
   * <p/>
   * Usage:
   * <pre> <code>
   *   analytics.onIntegrationReady(BundledIntegration.AMPLITUDE, new Callback() {
   *     {@literal @}Override public void onIntegrationReady(Object instance) {
   *       Amplitude.enableLocationListening();
   *     }
   *   });
   *   analytics.onIntegrationReady(BundledIntegration.MIXPANEL, new Callback() {
   *     {@literal @}Override public void onIntegrationReady(Object instance) {
   *       ((MixpanelAPI) instance).clearSuperProperties();
   *     }
   *   })*
   * </code> </pre>
   */
  public void onIntegrationReady(BundledIntegration bundledIntegration, Callback callback) {
    if (bundledIntegration == null) {
      throw new IllegalArgumentException("bundledIntegration cannot be null.");
    }
    if (integrationManager == null) {
      throw new IllegalStateException("Enable bundled integrations to register for this callback.");
    }

    integrationManager.dispatchRegisterCallback(bundledIntegration.key, callback);
  }

  void submit(BasePayload payload) {
    if (logLevel.log()) {
      debug(OWNER_MAIN, VERB_CREATE, payload.id(), payload);
    }
    segmentDispatcher.dispatchEnqueue(payload);
    if (integrationManager == null) {
      if (logLevel.log()) {
        debug(OWNER_INTEGRATION_MANAGER, VERB_SKIP, payload.id());
      }
    } else {
      integrationManager.dispatchOperation(payload);
    }
  }

  void submit(ActivityLifecyclePayload payload) {
    // We only register for lifecycle callbacks if integrationManager != null, so this method
    // is never called when integrationManager == null
    if (logLevel.log()) {
      debug(OWNER_MAIN, VERB_CREATE, payload.id(), payload);
    }
    integrationManager.dispatchOperation(payload);
  }

  public enum BundledIntegration {
    AMPLITUDE("Amplitude"),
    APPS_FLYER("AppsFlyer"),
    BUGSNAG("Bugsnag"),
    COUNTLY("Countly"),
    CRITTERCISM("Crittercism"),
    FLURRY("Flurry"),
    GOOGLE_ANALYTICS("Google Analytics"),
    KAHUNA("Kahuna"),
    LEANPLUM("Leanplum"),
    LOCALYTICS("Leanplum"),
    MIXPANEL("Mixpanel"),
    QUANTCAST("Quancast"),
    TAPSTREAM("Tapstream");

    /** The key that identifies this integration in our API. */
    final String key;

    BundledIntegration(String key) {
      this.key = key;
    }
  }

  /** Controls the level of logging. */
  public enum LogLevel {
    /** No logging. */
    NONE,
    /** Log exceptions and events through the Segment SDK only. */
    BASIC,
    /**
     * Log exceptions, events through the Segment SDK, and enable logging for bundled integrations.
     */
    INFO,
    /**
     * Log exceptions, events through the SDK, and enable verbose logging for bundled integrations.
     */
    VERBOSE;

    public boolean log() {
      return this != NONE;
    }
  }

  /**
   * A callback interface that is invoked when the Analytics client initializes bundled
   * integrations.
   */
  public interface Callback {
    /**
     * This method will be invoked once for each callback.
     *
     * @param instance The underlying instance that has been initialized with the settings from
     * Segment.
     */
    void onReady(Object instance);
  }

  /** Fluent API for creating {@link Analytics} instances. */
  public static class Builder {
    private final Application application;
    private String writeKey;
    private String tag;
    private int queueSize = Utils.DEFAULT_FLUSH_QUEUE_SIZE;
    private int flushInterval = Utils.DEFAULT_FLUSH_INTERVAL;
    private Options defaultOptions;
    private LogLevel logLevel;
    private boolean skipBundledIntegrations = false;

    /** Start building a new {@link Analytics} instance. */
    public Builder(Context context, String writeKey) {
      if (context == null) {
        throw new IllegalArgumentException("Context must not be null.");
      }
      if (!hasPermission(context, Manifest.permission.INTERNET)) {
        throw new IllegalArgumentException("INTERNET permission is required.");
      }
      if (isNullOrEmpty(writeKey)) {
        throw new IllegalArgumentException("writeKey must not be null or empty.");
      }
      application = (Application) context.getApplicationContext();
      this.writeKey = writeKey;
    }

    /**
     * Set the queue size at which the client should flush events. The client will automatically
     * flush events every {@code flushInterval} seconds, or when the queue reaches {@code
     * queueSize}, whichever occurs first.
     */
    public Builder queueSize(int queueSize) {
      if (queueSize <= 0) {
        throw new IllegalArgumentException("queueSize must be greater than or equal to zero.");
      }
      this.queueSize = queueSize;
      return this;
    }

    /**
     * Set the interval (in seconds) at which the client should flush events. The client will
     * automatically flush events every {@code flushInterval} seconds, or when the queue reaches
     * {@code queueSize}, whichever occurs first.
     */
    public Builder flushInterval(int flushInterval) {
      if (flushInterval < 10) {
        throw new IllegalArgumentException("flushInterval must be greater than or equal to 10.");
      }
      this.flushInterval = flushInterval;
      return this;
    }

    /**
     * Set some default options for all calls.
     *
     * @see {@link Options}
     */
    public Builder defaultOptions(Options defaultOptions) {
      if (defaultOptions == null) {
        throw new IllegalArgumentException("defaultOptions must not be null.");
      }
      if (defaultOptions.timestamp() != null) {
        throw new IllegalArgumentException("default option must not contain timestamp.");
      }
      // Make a defensive copy
      this.defaultOptions = new Options();
      for (Map.Entry<String, Boolean> entry : defaultOptions.integrations().entrySet()) {
        this.defaultOptions.setIntegration(entry.getKey(), entry.getValue());
      }
      return this;
    }

    /**
     * Set a tag for this instance. The tag is used to generate keys for caching.
     * </p>
     * By default the writeKey is used. You may want to specify an alternative one, if you want
     * the instances with the same writeKey to share different caches (you probably do).
     */
    public Builder tag(String tag) {
      if (isNullOrEmpty(tag)) {
        throw new IllegalArgumentException("tag must not be null or empty.");
      }
      this.tag = tag;
      return this;
    }

    /** Set a {@link LogLevel} for this instance. */
    public Builder logLevel(LogLevel logLevel) {
      if (logLevel == null) {
        throw new IllegalArgumentException("LogLevel must not be null.");
      }
      this.logLevel = logLevel;
      return this;
    }

    /**
     * Disable bundled integrations.
     * <p/>
     * This will skip *ALL* bundled integrations, even if they don't have a server side integration
     * available (e.g. Flurry).
     *
     * @see <a href="https://segment.com/help/getting-started/why-bundle-integrations/">Bundled
     * Integrations</a>
     */
    public Builder skipBundledIntegrations() {
      this.skipBundledIntegrations = true;
      return this;
    }

    /** Create a {@link Analytics} client. */
    public Analytics build() {
      if (defaultOptions == null) {
        defaultOptions = new Options();
      }
      if (logLevel == null) {
        logLevel = LogLevel.NONE;
      }
      if (isNullOrEmpty(tag)) {
        tag = writeKey;
      }

      Stats stats = new Stats();
      Cartographer cartographer = Cartographer.INSTANCE;
      Client client = new Client(application, writeKey);

      IntegrationManager integrationManager = null;
      Map<String, Boolean> bundledIntegrations;
      if (skipBundledIntegrations) {
        bundledIntegrations = Collections.emptyMap();
      } else {
        integrationManager =
            IntegrationManager.create(application, cartographer, client, stats, tag, logLevel);
        bundledIntegrations = Collections.unmodifiableMap(integrationManager.bundledIntegrations);
      }

      SegmentDispatcher segmentDispatcher =
          SegmentDispatcher.create(application, client, cartographer, stats, bundledIntegrations,
              tag, flushInterval, queueSize, logLevel);

      Traits.Cache traitsCache = new Traits.Cache(application, cartographer, tag);
      if (!traitsCache.isSet() || traitsCache.get() == null) {
        Traits traits = Traits.create();
        traitsCache.set(traits);
      }
      AnalyticsContext analyticsContext = AnalyticsContext.create(application, traitsCache.get());

      return new Analytics(application, integrationManager, segmentDispatcher, stats, traitsCache,
          analyticsContext, defaultOptions, logLevel);
    }
  }
}
