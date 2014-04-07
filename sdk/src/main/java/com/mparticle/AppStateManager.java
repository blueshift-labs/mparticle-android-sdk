package com.mparticle;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by sdozor on 1/15/14.
 */
class AppStateManager {

    public static final String APP_STATE_FOREGROUND = "foreground";
    public static final String APP_STATE_BACKGROUND = "background";
    public static final String APP_STATE_NOTRUNNING = "not_running";
    private final SharedPreferences preferences;
    Context mContext;
    AtomicInteger mActivities = new AtomicInteger(0);
    long mLastStoppedTime;
    Handler delayedBackgroundCheckHandler = new Handler();

    Runnable backgroundChecker = new Runnable() {
        @Override
        public void run() {
            if (isBackgrounded()) {
                MParticle.getInstance().logStateTransition(Constants.StateTransitionType.STATE_TRANS_BG);
                if (MParticle.getInstance().getDebugMode()) {
                    Log.d(Constants.LOG_TAG, "APP BACKGROUNDED");
                }
            }
        }
    };

    //it can take some time between when an activity stops and when a new one (or the same one)
    //starts again, so don't declared that we're backgrounded immediately.
    private static final long ACTIVITY_DELAY = 1000;

    public AppStateManager(Context context) {
        mContext = context.getApplicationContext();
        mLastStoppedTime = System.currentTimeMillis();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            setupLifecycleCallbacks();
        }
        preferences = context.getSharedPreferences(Constants.PREFS_FILE, Context.MODE_PRIVATE);
    }

    @TargetApi(14)
    private void setupLifecycleCallbacks() {
        ((Application) mContext).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {

            }

            @Override
            public void onActivityStarted(Activity activity) {
                AppStateManager.this.recordActivityStarted(activity);
            }

            @Override
            public void onActivityResumed(Activity activity) {

            }

            @Override
            public void onActivityPaused(Activity activity) {

            }

            @Override
            public void onActivityStopped(Activity activity) {
                AppStateManager.this.recordActivityStopped(activity);
            }

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle outState) {

            }

            @Override
            public void onActivityDestroyed(Activity activity) {

            }
        });
    }

    boolean isBackgrounded() {
        return mActivities.get() < 1 && (System.currentTimeMillis() - mLastStoppedTime >= ACTIVITY_DELAY);
    }

    public void onActivityStarted(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            recordActivityStarted(activity);
        }
    }

    public void onActivityStopped(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            recordActivityStopped(activity);
        }
    }

    void recordActivityStarted(Activity activity){
        preferences.edit().putBoolean(Constants.PrefKeys.CRASHED_IN_FOREGROUND, true).commit();
        if (isBackgrounded() && mLastStoppedTime > 0) {
            long totalTimeInBackground = preferences.getLong(Constants.PrefKeys.TIME_IN_BG, -1);
            if (totalTimeInBackground > -1){
                totalTimeInBackground += (System.currentTimeMillis() - mLastStoppedTime);
            }else{
                totalTimeInBackground = 0;
            }

            preferences.edit().putLong(Constants.PrefKeys.TIME_IN_BG, totalTimeInBackground).commit();

            MParticle.getInstance().logStateTransition(Constants.StateTransitionType.STATE_TRANS_FORE);
            if (MParticle.getInstance().getDebugMode()) {
                Log.d(Constants.LOG_TAG, "APP FOREGROUNDED");
            }


        }
        mActivities.getAndIncrement();
        if (MParticle.getInstance().getDebugMode()) {
            Log.d(Constants.LOG_TAG, "Activity Count: " + mActivities);
        }
        if (MParticle.getInstance().isAutoTrackingEnabled()) {
            MParticle.getInstance().logScreen(getActivityName(activity), null, true);
        }
    }

    void recordActivityStopped(Activity activity) {
        preferences.edit().putBoolean(Constants.PrefKeys.CRASHED_IN_FOREGROUND, false).commit();
        mLastStoppedTime = System.currentTimeMillis();

        if (mActivities.decrementAndGet() < 1) {
            delayedBackgroundCheckHandler.postDelayed(backgroundChecker, ACTIVITY_DELAY);
        }
        if (MParticle.getInstance().getDebugMode()) {
            Log.d(Constants.LOG_TAG, "Activity Count: " + mActivities);
        }
        if (MParticle.getInstance().isAutoTrackingEnabled()) {
            MParticle.getInstance().logScreen(getActivityName(activity), null, false);
        }

    }

    private String getActivityName(Activity activity) {
        return activity.getClass().getCanonicalName();
        /*if (this.mActivityNameMap.containsKey(canonicalName)) {
            return (String)this.mActivityNameMap.get(canonicalName);
        }
        String name = this.mParameterFetcher.getString(canonicalName);
        if (name == null) {
            name = canonicalName;
        }
        this.mActivityNameMap.put(canonicalName, name);
        return name;*/
    }
}
