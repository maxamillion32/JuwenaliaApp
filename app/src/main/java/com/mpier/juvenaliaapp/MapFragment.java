package com.mpier.juvenaliaapp;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.CameraUpdate;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.GroundOverlay;
import com.google.android.gms.maps.model.GroundOverlayOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;

/**
 * MapFragment, displaying marker if user is far from the target
 * or surroundings overlay if he/she is close.
 * <p/>
 * Created by Konpon96 on 2016-03-02.
 */
public class MapFragment extends Fragment implements OnMapReadyCallback, GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

    public static final int APP_PERMISSION_ACCESS_FINE_LOCATION = 1;

    private static BitmapDescriptor overlayBitmapDescriptor;

    private boolean isConnectedToInternet;

    private GoogleApiClient mGoogleApiClient;
    private GoogleMap mMap;

    private GroundOverlay overlay;
    private Marker marker;

    public MapFragment() {
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (mGoogleApiClient == null) {
            mGoogleApiClient = new GoogleApiClient.Builder(getContext())
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(LocationServices.API)
                    .build();
        }

        isConnectedToInternet = isDeviceConnectedToInternet();
    }

    @Override
    public void onStart() {
        mGoogleApiClient.connect();
        super.onStart();
    }

    @Override
    public void onStop() {
        mGoogleApiClient.disconnect();
        super.onStop();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.fragment_map, container, false);

        SupportMapFragment mapFragment = (SupportMapFragment) getChildFragmentManager()
                .findFragmentById(R.id.map);
        mapFragment.getMapAsync(this);

        return rootView;
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        mMap = googleMap;
        mMap.setOnMyLocationButtonClickListener(new GoogleMap.OnMyLocationButtonClickListener() {
            @Override
            public boolean onMyLocationButtonClick() {
                boolean isConnectedAtTheMoment = isDeviceConnectedToInternet();
                if (isConnectedAtTheMoment != isConnectedToInternet){
                    isConnectedToInternet = !isConnectedToInternet;
                    setMapMode(isConnectedToInternet);
                }
                updateMap(true);
                return true;
            }
        });

        // Android 6.0+ permissions
        if (ActivityCompat.checkSelfPermission(getActivity(), Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(getActivity(),
                    Manifest.permission.ACCESS_FINE_LOCATION)) {
                // User denied permission request earlier or in system settings - disable location layer
                setMyLocationEnabled(false);
            } else {
                // User didn't grant nor deny permission earlier - request permission
                ActivityCompat.requestPermissions(getActivity(),
                        new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                        APP_PERMISSION_ACCESS_FINE_LOCATION);
            }

        } else {
            // Permission was granted earlier - enable location layer
            setMyLocationEnabled(true);
        }

        // Creating overlay
        if (overlayBitmapDescriptor == null) {
            overlayBitmapDescriptor = BitmapDescriptorFactory.fromBitmap(decodeMapBitmap());
        }

        GroundOverlayOptions overlayOptions = new GroundOverlayOptions()
                .image(overlayBitmapDescriptor);

        LatLng southwest = new LatLng(52.211245, 21.008801);
        LatLng northeast = new LatLng(52.214225, 21.013685);
        overlayOptions.positionFromBounds(new LatLngBounds(southwest, northeast));

        overlay = mMap.addGroundOverlay(overlayOptions);

        // Creating marker
        marker = mMap.addMarker(new MarkerOptions()
                .position(new LatLng(52.21293, 21.01146))
                .title(getString(R.string.map_marker_stadium)));

        setMapMode(isConnectedToInternet);
        updateMap(true);

    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String permissions[],
                                           @NonNull int[] grantResults) {
        switch (requestCode) {
            case APP_PERMISSION_ACCESS_FINE_LOCATION: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Permission granted - enable location layer
                    setMyLocationEnabled(true);
                }
                break;
            }
        }

    }

    /**
     * Method wrapper, catching a SecurityException
     *
     * @param enabled If location layer on Google Map should be enabled
     */
    private void setMyLocationEnabled(boolean enabled) {
        try {
            mMap.setMyLocationEnabled(enabled);
        } catch (SecurityException e) {
            e.printStackTrace();
        }
    }

    /**
     * Method wrapper, catching a SecurityException
     *
     * @return Current user location or null in case of failure
     */
    private Location getLastLocation() {
        try {
            return LocationServices.FusedLocationApi.getLastLocation(mGoogleApiClient);
        } catch (SecurityException e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Update Google Map
     *
     * @param animate If to animate updating the map
     */
    private void updateMap(boolean animate) {

        LatLngBounds.Builder boundsBuilder = new LatLngBounds.Builder();

        // Main marker
        boundsBuilder.include(marker.getPosition());

        // User location (if location is enabled)
        Location lastLocation = null;
        if (mMap.isMyLocationEnabled() && mGoogleApiClient.isConnected()) {
            // Get user location and add to bounds builder
            lastLocation = getLastLocation();
            if (lastLocation != null) {
                boundsBuilder.include(new LatLng(lastLocation.getLatitude(), lastLocation.getLongitude()));
            }
        }

        // Move the map
        CameraUpdate cameraUpdate;
        if (mMap.isMyLocationEnabled()
                && lastLocation != null
                && isConnectedToInternet
                && !overlay.getBounds().contains(new LatLng(lastLocation.getLatitude(), lastLocation.getLongitude()))) {
            // If user location is determined and is not in the bounds of overlay
            cameraUpdate = CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(),
                    getResources().getDimensionPixelOffset(R.dimen.map_bounds_margin));
        } else {
            // If user location cannot be determined or is in the bounds of overlay
            cameraUpdate = CameraUpdateFactory.newLatLngBounds(overlay.getBounds(), 0);
        }

        if (animate) {
            mMap.animateCamera(cameraUpdate);
        } else {
            mMap.moveCamera(cameraUpdate);
        }

    }

    /**
     * Load map image from resource and decode it
     * into the non-scaled bitmap in order to display it on the map.
     *
     * @return Decoded bitmap
     */
    private Bitmap decodeMapBitmap() {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = false;
        options.inScaled = false;
        options.outWidth = 2048;
        options.outHeight = 2048;

        return BitmapFactory.decodeResource(getResources(), R.drawable.map, options);
    }

    /**
     * Sets Google Map mode to either use Google Maps background
     * or display the overlay only.
     *
     * @param online If true is passed, Google Maps will be loaded under the overlay
     */
    private void setMapMode(boolean online){
        if (online){
            overlay.setTransparency(0.25f);
            mMap.setMapType(GoogleMap.MAP_TYPE_NORMAL);
            mMap.setOnCameraChangeListener(new GoogleMap.OnCameraChangeListener() {
                @Override
                public void onCameraChange(CameraPosition cameraPosition) {
                    if (cameraPosition.zoom > 15.5) {
                        marker.setVisible(false);
                        overlay.setVisible(true);
                    } else {
                        marker.setVisible(true);
                        overlay.setVisible(false);
                        marker.showInfoWindow();
                    }
                }
            });

        } else {
            marker.setVisible(false);
            overlay.setTransparency(0f);
            mMap.setMapType(GoogleMap.MAP_TYPE_NONE);
            mMap.setOnCameraChangeListener(new GoogleMap.OnCameraChangeListener() {
                @Override
                public void onCameraChange(CameraPosition cameraPosition) {
                    // Does nothing
                }
            });

        }

    }

    /**
     * Connects with system's connectivity service to determine if
     * device is connected to the network.
     *
     * @return True if device is connected or connecting to the network
     */
    private boolean isDeviceConnectedToInternet(){
        ConnectivityManager cm =
                (ConnectivityManager)getContext().getSystemService(Context.CONNECTIVITY_SERVICE);

        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        return activeNetwork != null && activeNetwork.isConnectedOrConnecting();
    }

    @Override
    public void onConnected(Bundle bundle) {
        updateMap(true);
    }

    @Override
    public void onConnectionSuspended(int i) {
    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
        updateMap(false);
    }


}
