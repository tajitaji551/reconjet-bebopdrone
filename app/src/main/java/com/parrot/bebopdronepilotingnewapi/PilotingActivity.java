package com.parrot.bebopdronepilotingnewapi;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.RelativeLayout;
import android.widget.Toast;

import com.parrot.arsdk.arcommands.ARCOMMANDS_ARDRONE3_PILOTINGSTATE_FLYINGSTATECHANGED_STATE_ENUM;
import com.parrot.arsdk.arcontroller.ARCONTROLLER_DEVICE_STATE_ENUM;
import com.parrot.arsdk.arcontroller.ARCONTROLLER_DICTIONARY_KEY_ENUM;
import com.parrot.arsdk.arcontroller.ARCONTROLLER_ERROR_ENUM;
import com.parrot.arsdk.arcontroller.ARControllerArgumentDictionary;
import com.parrot.arsdk.arcontroller.ARControllerDictionary;
import com.parrot.arsdk.arcontroller.ARControllerException;
import com.parrot.arsdk.arcontroller.ARDeviceController;
import com.parrot.arsdk.arcontroller.ARDeviceControllerListener;
import com.parrot.arsdk.arcontroller.ARDeviceControllerStreamListener;
import com.parrot.arsdk.arcontroller.ARFrame;
import com.parrot.arsdk.ardiscovery.ARDISCOVERY_PRODUCT_ENUM;
import com.parrot.arsdk.ardiscovery.ARDiscoveryDevice;
import com.parrot.arsdk.ardiscovery.ARDiscoveryDeviceNetService;
import com.parrot.arsdk.ardiscovery.ARDiscoveryDeviceService;
import com.parrot.arsdk.ardiscovery.ARDiscoveryException;
import com.parrot.arsdk.armedia.ARMediaManager;
import com.parrot.arsdk.armedia.ARMediaNotificationReceiver;
import com.parrot.arsdk.armedia.ARMediaNotificationReceiverListener;
import com.parrot.bebopdronepilotingnewapi.view.MeterView;
import com.reconinstruments.os.HUDOS;
import com.reconinstruments.os.hardware.sensors.HUDHeadingManager;
import com.reconinstruments.os.hardware.sensors.HeadLocationListener;

import java.nio.ByteBuffer;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class PilotingActivity extends Activity
        implements HeadLocationListener, // ヘッドマウント
        ARDeviceControllerListener, // デバイス
        ARDeviceControllerStreamListener, // 映像
        SurfaceHolder.Callback, // 映像
        ARMediaNotificationReceiverListener, // ドローンメディア？
        LocationListener // GPS
{
    private static String TAG = PilotingActivity.class.getSimpleName();
    public static String EXTRA_DEVICE_SERVICE = "pilotingActivity.extra.device.service";
    /**
     * PID制御パラメータ(比例制御定数)
     */
    public static final double Kp = 0.6;
    /**
     * PID制御パラメータ(積分制御定数)
     */
    public static final double Ki = 0.02;
    /**
     * PID制御パラメータ(微分制御定数)
     */
    public static final double Kd = 0.6;

    public ARDeviceController deviceController;
    public ARDiscoveryDeviceService service;
    public ARDiscoveryDevice device;

    private AlertDialog alertDialog;

    private RelativeLayout view;

    // video vars
    private static final String VIDEO_MIME_TYPE = "video/avc";
    private static final int VIDEO_DEQUEUE_TIMEOUT = 33000;
    private static final int VIDEO_WIDTH = 640;
    private static final int VIDEO_HEIGHT = 368;
    private SurfaceView sfView;
    MeterView meterView;
    private MediaCodec mediaCodec;
    private Lock readyLock;
    private boolean isCodecConfigured = false;
    private ByteBuffer csdBuffer;
    private boolean waitForIFrame = true;
    private ByteBuffer [] buffers;

    // media receiver
    private ARMediaNotificationReceiver receiver;

    HUDHeadingManager mHUDHeadingManager = null;
    // 飛行ステータス
    private int emergencyCounter = 2; // 0で緊急着陸
    public static boolean isLanding = true; // true:着陸状態、false:飛行状態
    private boolean isUpDownMode = false; // true:上下モード、false:先進交代モード

    // GPS関係
    LocationManager locationManager;
    Timer locationTimer;
    long time;
    private int gpsAccuracy;
    private double lat, lang;
    private static final int TWO_MINUTES = 1000 * 60 * 2;
    private Location current;

    // コマンド判定関係
    Thread PIDSynchronizerThread;
    PIDSynchronizer pidSynchronizer;
    int upDownState = 0;

    // ハンドラー
    Handler handler = new Handler();

    // Drone
    ARCOMMANDS_ARDRONE3_PILOTINGSTATE_FLYINGSTATECHANGED_STATE_ENUM _droneState;

    private void failSafe() {
        if (deviceController != null) {
            deviceController.getFeatureARDrone3().sendPilotingLanding();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_piloting);
        mHUDHeadingManager = (HUDHeadingManager) HUDOS.getHUDService(HUDOS.HUD_HEADING_SERVICE);

        initIHM();
        initVideoVars();

        Intent intent = getIntent();
        service = intent.getParcelableExtra(EXTRA_DEVICE_SERVICE);

        //create the device
        try
        {
            device = new ARDiscoveryDevice();
            ARDiscoveryDeviceNetService netDeviceService = (ARDiscoveryDeviceNetService) service.getDevice();
            Log.d(TAG, "name:"+netDeviceService.getName()+" ip:"+netDeviceService.getIp()+" port:"+netDeviceService.getPort());
            device.initWifi(ARDISCOVERY_PRODUCT_ENUM.ARDISCOVERY_PRODUCT_ARDRONE, netDeviceService.getName(), netDeviceService.getIp(), netDeviceService.getPort());
        }
        catch (ARDiscoveryException e)
        {
            e.printStackTrace();
            Log.e(TAG, "Error: " + e.getError());
        }


        if (device != null)
        {
            try
            {
                //create the deviceController
                deviceController = new ARDeviceController (device);
                deviceController.addListener(this);
                deviceController.addStreamListener(this);
            }
            catch (ARControllerException e)
            {
                e.printStackTrace();
            }
        }
    }

    private void initIHM ()
    {
        view = (RelativeLayout) findViewById(R.id.piloting_view);
    }

    @Override
    public void onStart()
    {
        super.onStart();
        mHUDHeadingManager.register(this);
        startDeviceController();

        // ブロードキャスト
        if (receiver == null) {
            receiver = new ARMediaNotificationReceiver(this);
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(ARMediaManager.ARMediaManagerNotificationDictionaryIsInitKey);
        filter.addAction(ARMediaManager.ARMediaManagerNotificationDictionaryMediaAddedKey);
        filter.addAction(ARMediaManager.ARMediaManagerNotificationDictionaryUpdatedKey);
        filter.addAction(ARMediaManager.ARMediaManagerNotificationDictionaryUpdatingKey);
        registerReceiver(receiver, filter);
        //enableGPS();
        // 制御開始
        Log.d("PILOT", "onStart");
        if (PIDSynchronizerThread == null && deviceController != null) {
            pidSynchronizer = new PIDSynchronizer(handler, deviceController);
            pidSynchronizer.setMode(PIDSynchronizer.MODE_NORMAL);
            PIDSynchronizerThread = new Thread(pidSynchronizer);
            PIDSynchronizerThread.start();
        }
    }

    private void sendParameters(boolean isOutside) {
        // 屋外用
        if (isOutside) {

        }
        // 屋内用
        else {

        }
    }

    private void enableGPS() {
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (locationManager == null) return;
        final Criteria criteria = new Criteria();
        criteria.setBearingRequired(false);
        criteria.setSpeedRequired(false);
        final String provider = locationManager.getBestProvider(criteria, true);
        if (provider == null) {
            disableGPS();
        }
        // 5分以内の位置情報があるか
        final Location lastKnownLocation = locationManager.getLastKnownLocation(provider);
        if (lastKnownLocation != null && (new Date().getTime() - lastKnownLocation.getTime()) <= (5 * 60 * 1000L)) {
            setLocation(lastKnownLocation);
            return;
        }
        locationTimer = new Timer(true);
        locationTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (time == 1000L) {
                            Toast.makeText(PilotingActivity.this, "現在地を特定しています。", Toast.LENGTH_LONG).show();
                        } else if (time >= (30 * 1000L)) {
                            Toast.makeText(PilotingActivity.this, "現在地を特定できませんでした。", Toast.LENGTH_LONG).show();
                            disableGPS();
                        }
                        time = time + 1000L;
                    }
                });
            }
        }, 0, 1000);
        locationManager.requestLocationUpdates(provider, 60000, 0, this);
    }

    private void disableGPS() {
        if (locationTimer != null) {
            locationTimer.cancel();
            locationTimer.purge();
            locationTimer = null;
        }
        if (locationManager != null) {
            locationManager.removeUpdates(this);
            locationManager = null;
        }
    }

    private void setLocation(Location location) {
        Toast.makeText(PilotingActivity.this, "現在地を特定できました。", Toast.LENGTH_LONG).show();
        current = location;
        // ホーム更新
        if (deviceController != null) {
            ARCONTROLLER_ERROR_ENUM error = deviceController.getFeatureARDrone3().sendGPSSettingsSendControllerGPS(
                    current.getLatitude(),
                    current.getLongitude(),
                    current.getAltitude(),
                    current.getAccuracy(),
                    current.getAccuracy()
            );
        }
    }

    /**
     * D-Pad制御
     * @param keyCode
     * @param event
     * @return
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        ARCONTROLLER_ERROR_ENUM error;
        System.out.println("onKeyDown code:" + keyCode + " action:" + event.getAction() + " hoge:" + event.getCharacters());
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                System.out.println("D-PAD UP pressed:" + keyCode);
                if (deviceController != null) {
                    if (isUpDownMode) {
                        int gaz = PIDSynchronizer.mode == PIDSynchronizer.MODE_ENTRY
                                ? 30 : PIDSynchronizer.mode == PIDSynchronizer.MODE_NORMAL
                                ? 50 : PIDSynchronizer.mode == PIDSynchronizer.MODE_PRO
                                ? 80 : 20;
                        if (!isUpDownMode) {
                            if (upDownState < 0) {
                                deviceController.getFeatureARDrone3().setPilotingPCMDGaz((byte) 0);
                            } else if (upDownState == 0) {
                                deviceController.getFeatureARDrone3().setPilotingPCMDGaz((byte) gaz);
                            }
                        }
                    }
                }
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                System.out.println("D-PAD DOWN pressed:" + keyCode);
                if (deviceController != null) {
                    if (isUpDownMode) {
                        int gaz = PIDSynchronizer.mode == PIDSynchronizer.MODE_ENTRY
                                ? 30 : PIDSynchronizer.mode == PIDSynchronizer.MODE_NORMAL
                                ? 50 : PIDSynchronizer.mode == PIDSynchronizer.MODE_PRO
                                ? 80 : 20;
                        if (!isUpDownMode) {
                            if (upDownState > 0) {
                                deviceController.getFeatureARDrone3().setPilotingPCMDGaz((byte) 0);
                            } else if (upDownState == 0) {
                                deviceController.getFeatureARDrone3().setPilotingPCMDGaz((byte) -gaz);
                            }
                        }
                    } else {
                        deviceController.getFeatureARDrone3().sendPilotingLanding();
                    }
                }
                break;
            // 写真を撮る
            case KeyEvent.KEYCODE_DPAD_LEFT:
                System.out.println("D-PAD LEFT pressed:" + keyCode);
                if (deviceController != null) {
                    error = deviceController.getFeatureARDrone3().sendMediaRecordPictureV2();
                }
                break;
            // 飛行モード切り替え
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                System.out.println("D-PAD RIGHT pressed:" + keyCode);
                if (deviceController != null) {
                    isUpDownMode = !isUpDownMode;
                    if (meterView != null) meterView.changeUpsideDown(isUpDownMode);
                }
                break;
            // バックキーは緊急着陸
            case KeyEvent.KEYCODE_POWER:
                System.out.println("BACK pressed:" + keyCode);
                if (emergencyCounter > 0) {
                    emergencyCounter--;
                    return true;
                }
                if (deviceController != null && emergencyCounter <= 0) {
                    error = deviceController.getFeatureARDrone3().sendPilotingEmergency();
                    emergencyCounter = 2;
                    stopDeviceController();
                }
                break;
            // 選択ボタンで離着陸
            case KeyEvent.KEYCODE_DPAD_CENTER:
                System.out.println("SELECT pressed:" + keyCode);
                if (deviceController == null) break;

                if (!isLanding) {
                    error = deviceController.getFeatureARDrone3().sendPilotingLanding();
                    isLanding = true;
                } else {
                    error = deviceController.getFeatureARDrone3().sendPilotingTakeOff();
                    isLanding = false;
                }

                break;
            default:
                System.out.println("Button pressed:" + keyCode);
                break;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void startDeviceController() {
        if (deviceController != null) {
            final AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(PilotingActivity.this);
            // set title
            alertDialogBuilder.setTitle("Connecting ...");
            // create alert dialog
            alertDialog = alertDialogBuilder.create();
            alertDialog.show();

            ARCONTROLLER_ERROR_ENUM error = deviceController.start();

            if (error != ARCONTROLLER_ERROR_ENUM.ARCONTROLLER_OK)
            {
                finish();
            }
        }
    }

    private void stopDeviceController()
    {
        if (deviceController != null)
        {
            // show it
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    ARCONTROLLER_ERROR_ENUM error = deviceController.stop();
                    if (error != ARCONTROLLER_ERROR_ENUM.ARCONTROLLER_OK) {
                        finish();
                    }
                }
            });
        }
    }

    @Override
    protected void onStop()
    {
        isLanding = true;
        pidSynchronizer.term();
        mHUDHeadingManager.unregister(this);
        if (deviceController != null)
        {
            stopDeviceController();
        }

        // ブロードキャスト解除
        unregisterReceiver(receiver);

        super.onStop();
    }

    /**
     * 頭の動き検知
     * @param yaw 北0度 時計回りに360度まで
     * @param pitch 上向き-90度, 下向き+90度
     * @param roll 右向き-90度, 左向き+90度
     */
    @Override
    public void onHeadLocation(final float yaw, final float pitch, final float roll) {
        //Log.d("HEAD", yaw + " " + pitch + " " + roll);
        pidSynchronizer.updateTargetYPRState((double) yaw, (double) pitch, (double) roll);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (meterView != null) {
                    meterView.updateJet(yaw, pitch, roll);
                }
            }
        });
    }

    public void onUpdateBattery(final int percent) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (meterView != null) meterView.updateBattery(percent);
            }
        });
    }

    public void onUpdateSpeed(final double dx, final double dy, final double dz) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (meterView != null) meterView.updateSpeed(dx, dy, dz);
            }
        });
    }

    public void onUpdateLatLang(final double lat, final double lang, final double alti) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (meterView != null) meterView.updateGPS(lat, lang, alti);
            }
        });
    }

    public void onUpdateYawPitchRoll(final double yaw, final double pitch, final double roll) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                pidSynchronizer.updateCurrentYPRState(yaw, pitch, roll);
                double y = yaw, p = pitch, r = roll;
                if (meterView != null) {
                    if (y >= 0 && y <= 180) {
                        y = y * 60;
                    } else {
                        y += 3.0;
                        y *= 60;
                        y += 180;
                    }
                    p = -pitch * 90 / 1.5;
                    r = roll * 30;
                    //Log.d("upYPR", "updateYawPitchRoll " + y + " " + p + " " + r);
                    meterView.updateDrone((float) y, (float) p, (float) r);
                }
            }
        });
    }

    @Override
    public void onStateChanged (ARDeviceController deviceController, ARCONTROLLER_DEVICE_STATE_ENUM newState, ARCONTROLLER_ERROR_ENUM error) {
        Log.i(TAG, "onStateChanged ... newState:" + newState+" error: "+ error );

        switch (newState)
        {
            case ARCONTROLLER_DEVICE_STATE_RUNNING:
                //The deviceController is started
                Log.i(TAG, "ARCONTROLLER_DEVICE_STATE_RUNNING ....." );
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        //alertDialog.hide();
                        alertDialog.dismiss();
                    }
                });
                deviceController.getFeatureARDrone3().sendMediaStreamingVideoEnable((byte)1);
                break;

            case ARCONTROLLER_DEVICE_STATE_STOPPED:
                //The deviceController is stoped
                Log.i(TAG, "ARCONTROLLER_DEVICE_STATE_STOPPED ....." );

                deviceController.dispose();
                deviceController = null;

                runOnUiThread(new Runnable() {
                    @Override
                    public void run()
                    {
                        //alertDialog.hide();
                        alertDialog.dismiss();
                        finish();
                    }
                });
                break;

            default:
                break;
        }
    }

    @Override
    public void onCommandReceived(ARDeviceController deviceController, ARCONTROLLER_DICTIONARY_KEY_ENUM commandKey, ARControllerDictionary elementDictionary)
    {
        if (elementDictionary != null)
        {
            ARControllerArgumentDictionary<Object> args = elementDictionary.get(ARControllerDictionary.ARCONTROLLER_DICTIONARY_SINGLE_KEY);
            if (args != null) {
                //Log.d("Command", "key:" + commandKey);
                // バッテリー残量
                if (commandKey == ARCONTROLLER_DICTIONARY_KEY_ENUM.ARCONTROLLER_DICTIONARY_KEY_COMMON_COMMONSTATE_BATTERYSTATECHANGED) {
                    Integer batValue = (Integer) args.get("arcontroller_dictionary_key_common_commonstate_batterystatechanged_percent");
                    onUpdateBattery(batValue);
                }
                // ポジション
                else if (commandKey == ARCONTROLLER_DICTIONARY_KEY_ENUM.ARCONTROLLER_DICTIONARY_KEY_ARDRONE3_PILOTINGSTATE_POSITIONCHANGED) {
                    Double latValue = (Double) args.get("arcontroller_dictionary_key_ardrone3_pilotingstate_positionchanged_latitude");
                    Double langValue = (Double) args.get("arcontroller_dictionary_key_ardrone3_pilotingstate_positionchanged_longitude");
                    Double altiValue = (Double) args.get("arcontroller_dictionary_key_ardrone3_pilotingstate_positionchanged_altitude");
//                    onUpdateLatLang(latValue, langValue, altiValue);
                }
                // 速度変化
                else if (commandKey == ARCONTROLLER_DICTIONARY_KEY_ENUM.ARCONTROLLER_DICTIONARY_KEY_ARDRONE3_PILOTINGSTATE_SPEEDCHANGED) {
                    Double speedXValue = (Double) args.get("arcontroller_dictionary_key_ardrone3_pilotingstate_speedchanged_speedx");
                    Double speedYValue = (Double) args.get("arcontroller_dictionary_key_ardrone3_pilotingstate_speedchanged_speedy");
                    Double speedZValue = (Double) args.get("arcontroller_dictionary_key_ardrone3_pilotingstate_speedchanged_speedz");
//                    onUpdateSpeed(speedXValue, speedYValue, speedZValue);
                }
                /**
                 * roll: -3(進行方向左回り) ~ +3(進行方向右回り)
                 * pitch: -1.5(下向き垂直時) ~ +1.5(上向き垂直時)
                 * yaw: -3(南) -1.5(西)~ 0(北) ~ +1.5(東) +3(南)
                 */
                else if (commandKey == ARCONTROLLER_DICTIONARY_KEY_ENUM.ARCONTROLLER_DICTIONARY_KEY_ARDRONE3_PILOTINGSTATE_ATTITUDECHANGED) {
                    Double yawValue = (Double) args.get("arcontroller_dictionary_key_ardrone3_pilotingstate_attitudechanged_yaw");
                    Double pitchValue = (Double) args.get("arcontroller_dictionary_key_ardrone3_pilotingstate_attitudechanged_pitch");
                    Double rollValue = (Double) args.get("arcontroller_dictionary_key_ardrone3_pilotingstate_attitudechanged_roll");
                    //Log.d("機体情報", "yaw:" + yawValue + " pitch:" + pitchValue + " roll:" + rollValue);
                    onUpdateYawPitchRoll(yawValue, pitchValue, rollValue);
                }
            }
        }
        else {
            Log.e(TAG, "elementDictionary is null");
        }
    }

    @Override
    public void onFrameReceived(ARDeviceController deviceController, ARFrame frame)
    {
        readyLock.lock();

        if ((mediaCodec != null))
        {
            if (!isCodecConfigured && frame.isIFrame())
            {
                csdBuffer = getCSD(frame);
                if (csdBuffer != null)
                {
                    configureMediaCodec();
                }
            }
            if (isCodecConfigured && (!waitForIFrame || frame.isIFrame()))
            {
                waitForIFrame = false;

                // Here we have either a good PFrame, or an IFrame
                int index = -1;

                try
                {
                    index = mediaCodec.dequeueInputBuffer(VIDEO_DEQUEUE_TIMEOUT);
                }
                catch (IllegalStateException e)
                {
                    Log.e(TAG, "Error while dequeue input buffer");
                }
                if (index >= 0)
                {
                    ByteBuffer b = buffers[index];
                    b.clear();
                    b.put(frame.getByteData(), 0, frame.getDataSize());
                    //ByteBufferDumper.dumpBufferStartEnd("PFRAME", b, 10, 4);
                    int flag = 0;
                    if (frame.isIFrame())
                    {
                        //flag = MediaCodec.BUFFER_FLAG_SYNC_FRAME | MediaCodec.BUFFER_FLAG_CODEC_CONFIG;
                    }

                    try
                    {
                        mediaCodec.queueInputBuffer(index, 0, frame.getDataSize(), 0, flag);
                    }
                    catch (IllegalStateException e)
                    {
                        Log.e(TAG, "Error while queue input buffer");
                    }

                }
                else
                {
                    waitForIFrame = true;
                }
            }

            // Try to display previous frame
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int outIndex = -1;
            try
            {
                outIndex = mediaCodec.dequeueOutputBuffer(info, 0);

                while (outIndex >= 0)
                {
                    mediaCodec.releaseOutputBuffer(outIndex, true);
                    outIndex = mediaCodec.dequeueOutputBuffer(info, 0);
                }
            }
            catch (IllegalStateException e)
            {
                Log.e(TAG, "Error while dequeue input buffer (outIndex)");
            }
        }


        readyLock.unlock();
    }

    @Override
    public void onFrameTimeout(ARDeviceController deviceController)
    {
        //Log.i(TAG, "onFrameTimeout ..... " );
    }

    //region video
    public void initVideoVars()
    {
        readyLock = new ReentrantLock();
        applySetupVideo();
    }


    private void applySetupVideo()
    {
        String deviceModel = Build.DEVICE;
        Log.d(TAG, "configuring HW video codec for device: [" + deviceModel + "]");
        RelativeLayout.LayoutParams fullP = new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT);
        sfView = new SurfaceView(getApplicationContext());
        sfView.setLayoutParams(fullP);
        sfView.getHolder().addCallback(this);
        view.addView(sfView, 0);
        meterView = new MeterView(this);
        meterView.setLayoutParams(fullP);
        float[] calibrated = new float[]{
                MainActivity.minYaw,
                MainActivity.maxYaw,
                MainActivity.minPitch,
                MainActivity.maxPitch,
                MainActivity.minRoll,
                MainActivity.maxRoll
        };
        meterView.initJetCalibration(calibrated);
        view.addView(meterView, 1);
    }

    @SuppressLint("NewApi")
    public void reset()
    {
        /* This will be run either before or after decoding a frame. */
        readyLock.lock();
        view.removeView(meterView);
        view.removeView(sfView);
        meterView = null;
        sfView = null;

        releaseMediaCodec();

        readyLock.unlock();
    }

    /**
     * Configure and start media codec
     * @param type
     */
    @SuppressLint("NewApi")
    private void initMediaCodec(String type)
    {
        try
        {
            mediaCodec = MediaCodec.createDecoderByType(type);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        if (csdBuffer != null)
        {
            configureMediaCodec();
        }
    }

    @SuppressLint("NewApi")
    private void configureMediaCodec()
    {
        MediaFormat format = MediaFormat.createVideoFormat("video/avc", VIDEO_WIDTH, VIDEO_HEIGHT);
        format.setByteBuffer("csd-0", csdBuffer);

        mediaCodec.configure(format, sfView.getHolder().getSurface(), null, 0);
        mediaCodec.start();

        buffers = mediaCodec.getInputBuffers();

        isCodecConfigured = true;
    }

    @SuppressLint("NewApi")
    private void releaseMediaCodec()
    {
        if ((mediaCodec != null) && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN))
        {
            if (isCodecConfigured)
            {
                mediaCodec.stop();
                mediaCodec.release();
            }
            isCodecConfigured = false;
            mediaCodec = null;
        }
    }

    public ByteBuffer getCSD(ARFrame frame)
    {
        int spsSize = -1;
        if (frame.isIFrame())
        {
            byte[] data = frame.getByteData();
            int searchIndex = 0;
            // we'll need to search the "00 00 00 01" pattern to find each header size
            // Search start at index 4 to avoid finding the SPS "00 00 00 01" tag
            for (searchIndex = 4; searchIndex <= frame.getDataSize() - 4; searchIndex ++)
            {
                if (0 == data[searchIndex  ] &&
                        0 == data[searchIndex+1] &&
                        0 == data[searchIndex+2] &&
                        1 == data[searchIndex+3])
                {
                    break;  // PPS header found
                }
            }
            spsSize = searchIndex;

            // Search start at index 4 to avoid finding the PSS "00 00 00 01" tag
            for (searchIndex = spsSize+4; searchIndex <= frame.getDataSize() - 4; searchIndex ++)
            {
                if (0 == data[searchIndex  ] &&
                        0 == data[searchIndex+1] &&
                        0 == data[searchIndex+2] &&
                        1 == data[searchIndex+3])
                {
                    break;  // frame header found
                }
            }
            int csdSize = searchIndex;

            byte[] csdInfo = new byte[csdSize];
            System.arraycopy(data, 0, csdInfo, 0, csdSize);
            return ByteBuffer.wrap(csdInfo);
        }
        return null;
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder)
    {
        readyLock.lock();
        initMediaCodec(VIDEO_MIME_TYPE);
        readyLock.unlock();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height)
    {
    }


    @SuppressLint("NewApi")
    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        readyLock.lock();
        releaseMediaCodec();
        readyLock.unlock();
    }

    @Override
    public void onNotificationDictionaryIsInit() {
        Log.d(TAG, "ディレクトリ初期化");
    }

    @Override
    public void onNotificationDictionaryIsUpdated(boolean b) {
        Log.d(TAG, "ディレクトリ更新済み");
    }

    @Override
    public void onNotificationDictionaryIsUpdating(double v) {
        Log.d(TAG, "ディレクトリ更新中");
    }

    @Override
    public void onNotificationDictionaryMediaAdded(String s) {
        Log.d(TAG, "ディレクトリにメディア追加:" + s);
    }

    @Override
    public void onLocationChanged(Location location) {
        if (isBetterLocation(location, current)) {
            setLocation(location);
            disableGPS();
        }
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onProviderDisabled(String provider) {
    }

    /** Determines whether one Location reading is better than the current Location fix
     * @param location  The new Location that you want to evaluate
     * @param currentBestLocation  The current Location fix, to which you want to compare the new one
     */
    protected boolean isBetterLocation(Location location, Location currentBestLocation) {
        if (currentBestLocation == null) {
            // A new location is always better than no location
            return true;
        }

        // Check whether the new location fix is newer or older
        long timeDelta = location.getTime() - currentBestLocation.getTime();
        boolean isSignificantlyNewer = timeDelta > TWO_MINUTES;
        boolean isSignificantlyOlder = timeDelta < -TWO_MINUTES;
        boolean isNewer = timeDelta > 0;

        // If it's been more than two minutes since the current location, use the new location
        // because the user has likely moved
        if (isSignificantlyNewer) {
            return true;
            // If the new location is more than two minutes older, it must be worse
        } else if (isSignificantlyOlder) {
            return false;
        }

        // Check whether the new location fix is more or less accurate
        int accuracyDelta = (int) (location.getAccuracy() - currentBestLocation.getAccuracy());
        boolean isLessAccurate = accuracyDelta > 0;
        boolean isMoreAccurate = accuracyDelta < 0;
        boolean isSignificantlyLessAccurate = accuracyDelta > 200;

        // Check if the old and new location are from the same provider
        boolean isFromSameProvider = isSameProvider(location.getProvider(),
                currentBestLocation.getProvider());

        // Determine location quality using a combination of timeliness and accuracy
        if (isMoreAccurate) {
            return true;
        } else if (isNewer && !isLessAccurate) {
            return true;
        } else if (isNewer && !isSignificantlyLessAccurate && isFromSameProvider) {
            return true;
        }
        return false;
    }

    /** Checks whether two providers are the same */
    private boolean isSameProvider(String provider1, String provider2) {
        if (provider1 == null) {
            return provider2 == null;
        }
        return provider1.equals(provider2);
    }
}
