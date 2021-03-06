package com.mpier.juvenaliaapp.selfie;


import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.hardware.Camera;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentTransaction;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.Toast;

import com.mpier.juvenaliaapp.FragmentReplacer;
import com.mpier.juvenaliaapp.R;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.atomic.AtomicBoolean;


@SuppressWarnings("deprecation")
public class SelfieFragment extends Fragment {
    private static String TAG = SelfieFragment.class.getName();
    private final AtomicBoolean isRunning;
    private Camera camera;
    private int cameraId;
    private Camera.PictureCallback pictureCallback;
    private FrameLayout previewFrame;
    private CameraPreview cameraPreview;
    private Bitmap photoBitmap;

    public static final int APP_PERMISSIONS_CAMERA = 1;

    private AtomicBoolean permissionsDenied;

    public SelfieFragment() {
        pictureCallback = new SelfiePictureCallback();
        isRunning = new AtomicBoolean(true);
        permissionsDenied = new AtomicBoolean(false);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setHasOptionsMenu(true);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        getActivity().setTitle(R.string.menu_selfie);

        isRunning.set(true);

        new Handler().post(new Runnable() {
            @Override
            public void run() {
                if (ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                        || ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {

                    requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE},
                            APP_PERMISSIONS_CAMERA);
                }
                else {
                    new CameraInitializer().execute();
                }
            }
        });

        return inflater.inflate(R.layout.fragment_selfie, container, false);
    }

    @Override
    public void onResume() {
        super.onResume();

        getView().findViewById(R.id.cameraLoading).setVisibility(View.VISIBLE);

        if (permissionsDenied.get()) {
            FragmentReplacer.switchFragment(getFragmentManager(), new NoCameraPermissionsFragment(), false);
        }
    }

    @Override
    public void onPause() {
        super.onPause();

        synchronized (isRunning) {
            if (camera != null) {
                isRunning.set(false);

                camera.stopPreview();
                camera.setPreviewCallback(null);
                camera.release();
                camera = null;
            }
        }
        if (previewFrame != null) {
            previewFrame.removeView(cameraPreview);

            previewFrame.setVisibility(View.GONE);
            if (getView() != null)
                getView().findViewById(R.id.buttonCapture).setVisibility(View.GONE);
        }
        if (photoBitmap != null) {
            photoBitmap.recycle();
        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);

        inflater.inflate(R.menu.selfie_menu, menu);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        switch (requestCode) {
            case APP_PERMISSIONS_CAMERA: {
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED
                        && grantResults[1] == PackageManager.PERMISSION_GRANTED) {
                    new CameraInitializer().execute();

                } else {
                    permissionsDenied.set(true);
                }
                return;
            }
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_selfie_info: {
                AlertDialog.Builder builder = new AlertDialog.Builder(getContext(), R.style.AppTheme_AlertDialog);
                builder.setMessage(getContext().getString(R.string.selfie_info_message))
                        .setTitle(getContext().getString(R.string.selfie_info_title))
                        .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                dialog.dismiss();
                            }
                        });
                AlertDialog alert = builder.create();
                alert.show();
                return true;
            }
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private int getCameraDisplayOrientation() {
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(cameraId, info);
        int rotation = getActivity().getWindowManager().getDefaultDisplay().getRotation();
        int degrees = 0;
        switch (rotation) {
            case Surface.ROTATION_0:
                degrees = 0;
                break;
            case Surface.ROTATION_90:
                degrees = 90;
                break;
            case Surface.ROTATION_180:
                degrees = 180;
                break;
            case Surface.ROTATION_270:
                degrees = 270;
                break;
        }

        int result;
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            result = (info.orientation + degrees) % 360;
            result = (360 - result) % 360;  // compensate the mirror
        } else {  // back-facing
            result = (info.orientation - degrees + 360) % 360;
        }
        return result;
    }

    private class CameraInitializer extends AsyncTask<Void, Void, Boolean> {
        private Bitmap logoBitmap;

        @Override
        protected Boolean doInBackground(Void... params) {
            boolean initializationSuccessful = false;

            try {
                int numberOfCameras = Camera.getNumberOfCameras();
                int idOfCameraFacingFront = -1;
                for (int i = 0; i < numberOfCameras; i++) {
                    Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
                    Camera.getCameraInfo(i, cameraInfo);
                    if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                        idOfCameraFacingFront = i;
                    }
                }
                if (idOfCameraFacingFront != -1) {
                    synchronized (isRunning) {
                        if (isRunning.get()) {
                            camera = Camera.open(idOfCameraFacingFront);
                        } else {
                            return false;
                        }
                    }

                    cameraId = idOfCameraFacingFront;

                    if (isRunning.get()) {
                        logoBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.logo_selfie);
                    } else {
                        return false;
                    }

                    initializationSuccessful = (camera != null);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to open Camera");
                Log.e(TAG, Log.getStackTraceString(e));
            }

            return initializationSuccessful;
        }

        @Override
        protected void onPostExecute(Boolean initializationSuccessful) {
            if (!initializationSuccessful) {
                synchronized (isRunning) {
                    if (isRunning.get()) {
                        FragmentReplacer.switchFragment(getFragmentManager(), new LackOfCameraFragment(), false);
                    }
                }
            } else {
                View view = getView();
                synchronized (isRunning) {
                    if (isRunning.get()) {
                        cameraPreview = new CameraPreview(getActivity(), camera, cameraId, logoBitmap);
                    }
                }
                if (cameraPreview != null) {
                    previewFrame = (FrameLayout) view.findViewById(R.id.cameraPreview);
                    FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER);
                    previewFrame.addView(cameraPreview, params);

                    final ImageButton button = (ImageButton) view.findViewById(R.id.buttonCapture);
                    button.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            button.setEnabled(false);
                            camera.takePicture(null, null, pictureCallback);
                        }
                    });

                    view.findViewById(R.id.cameraLoading).setVisibility(View.GONE);
                    previewFrame.setVisibility(View.VISIBLE);
                    view.findViewById(R.id.buttonCapture).setVisibility(View.VISIBLE);
                }
            }
        }
    }

    private class SelfiePictureCallback implements Camera.PictureCallback {
        @Override
        public void onPictureTaken(byte[] bitmapData, Camera camera) {
            new SelfieSaver().execute(bitmapData);
        }
    }

    private class SelfieSaver extends AsyncTask<byte[], Void, Void> {
        private boolean saveSuccessful;
        private File outputFile;
        private int destWidth;
        private int destHeigth;

        @Override
        protected void onPreExecute() {
            destHeigth = cameraPreview.getHeight();
            destWidth = cameraPreview.getWidth();
        }

        @Override
        protected Void doInBackground(byte[]... params) {
            byte[] bitmapData = params[0];

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inMutable = true;
            photoBitmap = BitmapFactory.decodeByteArray(bitmapData, 0, bitmapData.length, options);

            Matrix matrix = new Matrix();
            int rotation = getCameraDisplayOrientation();
            if (rotation != 0) {
                if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT) {
                    rotation += 180;
                }
                matrix.postRotate(rotation);
            }
            matrix.postScale(-1, 1);
            Bitmap rotated = Bitmap.createBitmap(photoBitmap, 0, 0, photoBitmap.getWidth(), photoBitmap.getHeight(), matrix, true);
            photoBitmap.recycle();
            photoBitmap = rotated;

            final Canvas canvas = new Canvas(photoBitmap);

            drawLogoOnCanvas(canvas);

            outputFile = getOutputImageFile();
            saveSuccessful = savePhoto(photoBitmap, outputFile);

            Bitmap scaled = Bitmap.createScaledBitmap(photoBitmap, destWidth, destHeigth, true);
            photoBitmap.recycle();
            photoBitmap = scaled;

            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            if (saveSuccessful) {
                View view = getView();
                if (view != null) {
                    getView().findViewById(R.id.cameraPreview).setVisibility(View.GONE);

                    ImageView photoView = (ImageView) getView().findViewById(R.id.photoPreview);
                    photoView.setImageBitmap(photoBitmap);
                    photoView.setVisibility(View.VISIBLE);

                    addPhotoToGallery(outputFile);

                    Toast.makeText(getActivity(), getActivity().getString(R.string.selfie_photo_saved), Toast.LENGTH_LONG).show();

                    sharePhoto(outputFile);
                }
            }
        }

        private File getOutputImageFile() {
            // To be safe, you should check that the SDCard is mounted
            // using Environment.getExternalStorageState() before doing this.

            File mediaStorageDir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DCIM), "JuwenaliaPW");

            if (!mediaStorageDir.exists()) {
                if (!mediaStorageDir.mkdirs()) {
                    Log.d(TAG, "failed to create directory");
                    return null;
                }
            }

            // Create a media file name
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());

            return new File(String.format("%s/IMG_%s.jpg", mediaStorageDir.getPath(), timeStamp));
        }

        private void drawLogoOnCanvas(Canvas canvas) {
            Bitmap logo = BitmapFactory.decodeResource(getResources(), R.drawable.logo_selfie);
            float logoWidth = logo.getWidth();
            float logoHeight = logo.getHeight();
            float ratio = logoHeight / logoWidth;

            int logoWidthOnPhoto = canvas.getWidth() / 3;
            int logoHeightOnPhoto = (int) (logoWidthOnPhoto * ratio);

            Bitmap scaledLogo = Bitmap.createScaledBitmap(logo, logoWidthOnPhoto, logoHeightOnPhoto, true);
            logo.recycle();

            canvas.drawBitmap(scaledLogo, canvas.getWidth() - logoWidthOnPhoto, canvas.getHeight() - logoHeightOnPhoto, null);
            scaledLogo.recycle();
        }

        private boolean savePhoto(Bitmap photoBitmap, File outputFile) {
            if (outputFile == null) {
                Log.d(TAG, "Error creating media file, check storage permissions");
                return false;
            }

            try {
                FileOutputStream fos = new FileOutputStream(outputFile);
                photoBitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos);
                fos.close();
            } catch (FileNotFoundException e) {
                Log.d(TAG, "File not found: " + e.getMessage());
                return false;
            } catch (IOException e) {
                Log.d(TAG, "Error accessing file: " + e.getMessage());
                return false;
            }

            return true;
        }

        private void addPhotoToGallery(File photoFile) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DATE_TAKEN, photoFile.lastModified());
            values.put(MediaStore.Images.Media.DATA, "image/jpeg");
            values.put(MediaStore.MediaColumns.DATA, photoFile.getPath());

            getContext().getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        }

        private void sharePhoto(File photoFile) {
            Fragment newFragment = new PhotoShareFragment();

            Bundle args = new Bundle();
            args.putString("photoFilePath", photoFile.getPath());
            newFragment.setArguments(args);

            FragmentReplacer.switchFragment(getFragmentManager(), newFragment, false);
        }
    }
}