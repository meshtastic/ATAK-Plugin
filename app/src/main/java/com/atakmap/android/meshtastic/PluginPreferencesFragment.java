package com.atakmap.android.meshtastic;


import android.annotation.SuppressLint;
import android.content.Context;

import android.os.Bundle;

import com.atakmap.android.meshtastic.plugin.R;

import com.atakmap.android.preference.PluginPreferenceFragment;

public class PluginPreferencesFragment extends PluginPreferenceFragment {

    @SuppressLint("StaticFieldLeak")
    private static Context pluginContext;

    public PluginPreferencesFragment() {
        super(pluginContext, R.xml.preferences);
    }

    @SuppressLint("ValidFragment")
    public PluginPreferencesFragment(final Context pluginContext) {
        super(pluginContext, R.xml.preferences);
        this.pluginContext = pluginContext;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public String getSubTitle() {
        return getSubTitle("Tool Preferences", pluginContext.getString(R.string.preferences_title));
    }
}