package com.atakmap.android.meshtastic;

import android.content.Context;
import android.graphics.Color;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.widgets.LinearLayoutWidget;
import com.atakmap.android.widgets.RootLayoutWidget;
import com.atakmap.coremap.maps.assets.Icon;
import com.atakmap.android.widgets.MarkerIconWidget;
import com.atakmap.coremap.log.Log;
import com.atakmap.android.meshtastic.plugin.R;


public class MeshtasticWidget extends MarkerIconWidget {
    private final static int ICON_WIDTH = 32;
    private final static int ICON_HEIGHT = 32;

    public static final String TAG = "meshtasticWidget";

    private final MapView _mapView = null;
    private final Context _plugin;


    public MeshtasticWidget(Context plugin, MapView mapView) {
        _mapView = mapView;
        _plugin = plugin;

        RootLayoutWidget root = (RootLayoutWidget) mapView.getComponentExtra("rootLayoutWidget");
        LinearLayoutWidget brLayout = root.getLayout(RootLayoutWidget.BOTTOM_RIGHT);
        brLayout.addWidget(this);
        setIcon("red");
    }

    public void setIcon(String color) {
        String imageUri;
        if (color.equalsIgnoreCase("red"))
            imageUri = "android.resource://com.atakmap.android.meshtastic.plugin/" + R.drawable.ic_red;
        else {
            imageUri = "android.resource://com.atakmap.android.meshtastic.plugin/" + R.drawable.ic_green;
        }

        Log.d(TAG, "imageURi " + imageUri);
        Icon.Builder builder = new Icon.Builder();
        builder.setAnchor(0, 0);

        if (color.equalsIgnoreCase("red"))
            builder.setColor(Icon.STATE_DEFAULT, Color.RED);
        else
            builder.setColor(Icon.STATE_DEFAULT, Color.GREEN);

        builder.setSize(ICON_WIDTH, ICON_HEIGHT);
        builder.setImageUri(Icon.STATE_DEFAULT, imageUri);

        Icon icon = builder.build();
        setIcon(icon);
    }

    public void destroy() {
        RootLayoutWidget root = (RootLayoutWidget) _mapView.getComponentExtra("rootLayoutWidget");
        LinearLayoutWidget brLayout = root.getLayout(RootLayoutWidget.BOTTOM_RIGHT);
        brLayout.removeWidget(this);
    }
}
