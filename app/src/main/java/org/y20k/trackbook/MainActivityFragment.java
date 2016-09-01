/**
 * MainActivityFragment.java
 * Implements the main fragment of the main activity
 * This fragment displays a map using osmdroid
 *
 * This file is part of
 * TRACKBOOK - Movement Recorder for Android
 *
 * Copyright (c) 2016 - Y20K.org
 * Licensed under the MIT-License
 * http://opensource.org/licenses/MIT
 *
 * Trackbook uses osmdroid - OpenStreetMap-Tools for Android
 * https://github.com/osmdroid/osmdroid
 */

package org.y20k.trackbook;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.Fragment;
import android.support.v4.content.LocalBroadcastManager;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import org.osmdroid.api.IMapController;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.ItemizedIconOverlay;
import org.osmdroid.views.overlay.compass.CompassOverlay;
import org.osmdroid.views.overlay.compass.InternalCompassOrientationProvider;
import org.y20k.trackbook.core.Track;
import org.y20k.trackbook.helpers.LocationHelper;
import org.y20k.trackbook.helpers.LogHelper;
import org.y20k.trackbook.helpers.MapHelper;
import org.y20k.trackbook.helpers.TrackbookKeys;

import java.util.List;


/**
 * MainActivityFragment class
 */
public class MainActivityFragment extends Fragment implements TrackbookKeys {

    /* Define log tag */
    private static final String LOG_TAG = MainActivityFragment.class.getSimpleName();


    /* Main class variables */
    private Activity mActivity;
    private Track mTrack;
    private boolean mFirstStart;
    private BroadcastReceiver mTrackUpdatedReceiver;
    private MapView mMapView;
    private IMapController mController;
    private LocationManager mLocationManager;
    private LocationListener mGPSListener;
    private LocationListener mNetworkListener;
    private ItemizedIconOverlay mMyLocationOverlay;
    private ItemizedIconOverlay mTrackOverlay;
    private Location mCurrentBestLocation;
    private boolean mTacking;


    /* Constructor (default) */
    public MainActivityFragment() {
    }


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // get activity
        mActivity = getActivity();

        // action bar has options menu
        setHasOptionsMenu(true);

        // restore first start state
        mFirstStart = true;
        if (savedInstanceState != null) {
            mFirstStart = savedInstanceState.getBoolean(INSTANCE_FIRST_START, true);
        }

        // restore tracking state
        mTacking = false;
        if (savedInstanceState != null) {
            mTacking = savedInstanceState.getBoolean(INSTANCE_TRACKING_STARTED, false);
        }

        // register broadcast receiver for new WayPoints
        mTrackUpdatedReceiver = createTrackUpdatedReceiver();
        IntentFilter trackUpdatedIntentFilter = new IntentFilter(ACTION_TRACK_UPDATED);
        LocalBroadcastManager.getInstance(mActivity).registerReceiver(mTrackUpdatedReceiver, trackUpdatedIntentFilter);

        // acquire reference to Location Manager
        mLocationManager = (LocationManager) mActivity.getSystemService(Context.LOCATION_SERVICE);

        // check if location services are available
        if (mLocationManager.getProviders(true).size() == 0) {
            // ask user to turn on location services
            promptUserForLocation();
        }

        // CASE 1: get saved location if possible
        if (savedInstanceState != null) {
            Location savedLocation = savedInstanceState.getParcelable(INSTANCE_CURRENT_LOCATION);
            // check if saved location is still current
            if (LocationHelper.isNewLocation(savedLocation)) {
                mCurrentBestLocation = savedLocation;
            } else {
                mCurrentBestLocation = null;
            }
        }

        // CASE 2: get last known location if no saved location or saved location is too old
        if (mCurrentBestLocation == null && mLocationManager.getProviders(true).size() > 0) {
            mCurrentBestLocation = LocationHelper.determineLastKnownLocation(mLocationManager);
        }

    }


    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        // create basic map
        mMapView = new MapView(inflater.getContext());

        // get display metrics
        final DisplayMetrics dm = mActivity.getResources().getDisplayMetrics();

        // get map controller
        mController = mMapView.getController();

        // basic map setup
        mMapView.setTileSource(TileSourceFactory.MAPNIK);
        mMapView.setTilesScaledToDpi(true);

        // add multi-touch capability
        mMapView.setMultiTouchControls(true);

        // initiate map state
        if (savedInstanceState != null) {
            // restore saved instance of map
            GeoPoint position = new GeoPoint(savedInstanceState.getDouble(INSTANCE_LATITUDE), savedInstanceState.getDouble(INSTANCE_LONGITUDE));
            mController.setCenter(position);
            mController.setZoom(savedInstanceState.getInt(INSTANCE_ZOOM_LEVEL, 16));
            // restore current location
            mCurrentBestLocation = savedInstanceState.getParcelable(INSTANCE_CURRENT_LOCATION);
        } else if (mCurrentBestLocation != null) {
            // fallback or first run: set map to current position
            GeoPoint position = new GeoPoint(mCurrentBestLocation.getLatitude(), mCurrentBestLocation.getLongitude());
            mController.setCenter(position);
            mController.setZoom(16);
        }

        // restore track
        if (savedInstanceState != null) {
            mTrack = savedInstanceState.getParcelable(INSTANCE_TRACK);
        }
        if (mTrack != null) {
            drawTrackOverlay(mTrack);
        }

        // add compass to map
        CompassOverlay compassOverlay = new CompassOverlay(mActivity, new InternalCompassOrientationProvider(mActivity), mMapView);
        compassOverlay.enableCompass();
        mMapView.getOverlays().add(compassOverlay);

        // mark user's location on map
        if (mCurrentBestLocation != null) {
            mMyLocationOverlay = MapHelper.createMyLocationOverlay(mActivity, mCurrentBestLocation, LocationHelper.isNewLocation(mCurrentBestLocation), mTacking);
            mMapView.getOverlays().add(mMyLocationOverlay);
        }

        return mMapView;
    }


    @Override
    public void onResume() {
        super.onResume();

        // start tracking position
        startFindingLocation();
    }


    @Override
    public void onPause() {
        super.onPause();

        // disable location listeners
        LocationHelper.removeLocationListeners(mLocationManager, mGPSListener, mNetworkListener);
    }


    @Override
    public void onDestroyView(){
        super.onDestroyView();

        // unregister broadcast receiver
        LocalBroadcastManager.getInstance(mActivity).unregisterReceiver(mTrackUpdatedReceiver);

        // deactivate map
        mMapView.onDetach();
    }


    @Override
    public void onDestroy() {
        // reset first start state
        mFirstStart = true;

        super.onDestroy();
    }


    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

        // handle action bar options menu selection
        switch (item.getItemId()) {

            // CASE MY LOCATION
            case R.id.action_my_location:

                Toast.makeText(mActivity, mActivity.getString(R.string.toast_message_my_location), Toast.LENGTH_LONG).show();

                if (mLocationManager.getProviders(true).size() == 0) {
                    // location services are off - ask user to turn them on
                    promptUserForLocation();
                    return true;
                }

                // get current position
                GeoPoint position;
                if (mCurrentBestLocation != null) {
                    // app has a current best estimate location
                    position = new GeoPoint(mCurrentBestLocation.getLatitude(), mCurrentBestLocation.getLongitude());
                } else {
                    // app does not have any location fix
                    mCurrentBestLocation = LocationHelper.determineLastKnownLocation(mLocationManager);
                    position = new GeoPoint(mCurrentBestLocation.getLatitude(), mCurrentBestLocation.getLongitude());
                }

                // center map on current position
                mController.setCenter(position);

                // mark user's new location on map and remove last marker
                updateMyLocationMarker();

                return true;

            // CASE DEFAULT
            default:
                return super.onOptionsItemSelected(item);
        }
    }


    @Override
    public void onSaveInstanceState(Bundle outState) {
        // save map position
        outState.putBoolean(INSTANCE_FIRST_START, mFirstStart);
        outState.putDouble(INSTANCE_LATITUDE, mMapView.getMapCenter().getLatitude());
        outState.putDouble(INSTANCE_LONGITUDE, mMapView.getMapCenter().getLongitude());
        outState.putInt(INSTANCE_ZOOM_LEVEL, mMapView.getZoomLevel());
        outState.putParcelable(INSTANCE_CURRENT_LOCATION, mCurrentBestLocation);
        outState.putParcelable(INSTANCE_TRACK, mTrack);
        outState.putBoolean(INSTANCE_TRACKING_STARTED, mTacking);
        super.onSaveInstanceState(outState);
    }


    /* Sets tracking state */
    public void setTrackingState (boolean trackingStarted) {
        mTacking = trackingStarted;
        updateMyLocationMarker();
    }


    /* Start finding location for map */
    private void startFindingLocation() {
        // create location listeners
        List locationProviders = mLocationManager.getProviders(true);
        if (locationProviders.contains(LocationManager.GPS_PROVIDER)) {
            mGPSListener = createLocationListener();
        }

        if (locationProviders.contains(LocationManager.NETWORK_PROVIDER)) {
            mNetworkListener = createLocationListener();
        }

        // register listeners
        LocationHelper.registerLocationListeners(mLocationManager, mGPSListener, mNetworkListener);
    }


    /* Creates listener for changes in location status */
    private LocationListener createLocationListener() {
        return new LocationListener() {
            public void onLocationChanged(Location location) {
                // check if the new location is better
                if (LocationHelper.isBetterLocation(location, mCurrentBestLocation)) {
                    // save location
                    mCurrentBestLocation = location;
                    LogHelper.v(LOG_TAG, "Location isBetterLocation(!): " + location.getProvider()); // TODO remove
                    // mark user's new location on map and remove last marker
                    updateMyLocationMarker();
                }
            }

            public void onStatusChanged(String provider, int status, Bundle extras) {
                // TODO do something
            }

            public void onProviderEnabled(String provider) {
                LogHelper.v(LOG_TAG, "Location provider enabled: " +  provider);
            }

            public void onProviderDisabled(String provider) {
                LogHelper.v(LOG_TAG, "Location provider disabled: " +  provider);
            }
        };
    }


    /* Updates marker for current user location  */
    private void updateMyLocationMarker() {
        mMapView.getOverlays().remove(mMyLocationOverlay);
        mMyLocationOverlay = MapHelper.createMyLocationOverlay(mActivity, mCurrentBestLocation, LocationHelper.isNewLocation(mCurrentBestLocation),mTacking);
        mMapView.getOverlays().add(mMyLocationOverlay);
    }


    /* Draws track onto overlay */
    private void drawTrackOverlay(Track track) {
        mMapView.getOverlays().remove(mTrackOverlay);
        mTrackOverlay = MapHelper.createTrackOverlay(mActivity, track);
        mMapView.getOverlays().add(mTrackOverlay);
    }


    /* Prompts user to turn on location */
    private void promptUserForLocation() {
        // TODO prompt user to turn on location
        Toast.makeText(mActivity, mActivity.getString(R.string.toast_message_location_offline), Toast.LENGTH_LONG).show();
    }


    /* Creates receiver for new WayPoints */
    private BroadcastReceiver createTrackUpdatedReceiver() {
        return new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.hasExtra(EXTRA_TRACK)) {
                    mTrack = intent.getParcelableExtra(EXTRA_TRACK);
                    drawTrackOverlay(mTrack);
                    Toast.makeText(mActivity, "New WayPoint.", Toast.LENGTH_LONG).show(); // TODO Remove
                }
            }
        };
    }


    /* Saves state of map */
    private void saveMaoState(Context context) {
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = settings.edit();
        editor.putInt(PREFS_ZOOM_LEVEL, mMapView.getZoomLevel());
        editor.apply();
    }


    /* Loads app state from preferences */
    private void loadMapState(Context context) {
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(context);
        int zoom = settings.getInt(PREFS_ZOOM_LEVEL, 16);
    }

}