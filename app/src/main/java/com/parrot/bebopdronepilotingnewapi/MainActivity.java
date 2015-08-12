package com.parrot.bebopdronepilotingnewapi;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.parrot.arsdk.ardiscovery.ARDISCOVERY_PRODUCT_ENUM;
import com.parrot.arsdk.ardiscovery.ARDiscoveryDeviceService;
import com.parrot.arsdk.ardiscovery.ARDiscoveryService;
import com.parrot.arsdk.ardiscovery.receivers.ARDiscoveryServicesDevicesListUpdatedReceiver;
import com.parrot.arsdk.ardiscovery.receivers.ARDiscoveryServicesDevicesListUpdatedReceiverDelegate;
import com.parrot.arsdk.arsal.ARSALPrint;
import com.reconinstruments.os.HUDOS;
import com.reconinstruments.os.hardware.sensors.HUDHeadingManager;
import com.reconinstruments.os.hardware.sensors.HeadLocationListener;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements HeadLocationListener, ARDiscoveryServicesDevicesListUpdatedReceiverDelegate
{
    private static String TAG = MainActivity.class.getSimpleName();

    static
    {
        try
        {
            System.loadLibrary("arsal");
            System.loadLibrary("arsal_android");
            System.loadLibrary("arnetworkal");
            System.loadLibrary("arnetworkal_android");
            System.loadLibrary("arnetwork");
            System.loadLibrary("arnetwork_android");
            System.loadLibrary("arcommands");
            System.loadLibrary("arcommands_android");
            System.loadLibrary("json");
            System.loadLibrary("ardiscovery");
            System.loadLibrary("ardiscovery_android");
            System.loadLibrary("arstream");
            System.loadLibrary("arstream_android");
            System.loadLibrary("arcontroller");
            System.loadLibrary("arcontroller_android");
        }
        catch (Exception e)
        {
            Log.e(TAG, "Oops (LoadLibrary)", e);
        }
    }

    private ListView listView ;
    private List<ARDiscoveryDeviceService> deviceList;
    private String[] deviceNameList;

    private ARDiscoveryService ardiscoveryService;
    private boolean ardiscoveryServiceBound = false;
    private ServiceConnection ardiscoveryServiceConnection;
    public IBinder discoveryServiceBinder;

    private BroadcastReceiver ardiscoveryServicesDevicesListUpdatedReceiver;

    private HUDHeadingManager mHUDHeadingManager = null;

    private RelativeLayout view;

    // Calibration
    private boolean isCalibrated = false;
    private boolean isInCalibration = false;
    private static final int CALIBRATION_STEP_MINMAX = 5;
    private static final int CALIBRATION_STEP_NORMAL = 4;
    private int calibrationCounter = 10;
    public static float minYaw = 10000, maxYaw = 10000, minPitch, maxPitch, minRoll, maxRoll;
    public static float norYaw, norPitch, norRoll;
    private List<Float> yawBuf = new ArrayList<Float>();
    private List<Float> pitchBuf = new ArrayList<Float>();
    private List<Float> rollBuf = new ArrayList<Float>();
    private TextView calibrationDirection;
    private Thread calibrationThread = new Thread(new Runnable() {
        @Override
        public void run() {
            try {
                isInCalibration = true;
                // init
                calibrationCounter = 9;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        calibrationDirection.setText(R.string.calibration_direction_1);
                        calibrationDirection.setTextSize(30);
                        RelativeLayout.LayoutParams p = new RelativeLayout.LayoutParams(-1, -1);
                        view.addView(calibrationDirection, p);
                    }
                });
                Thread.sleep(5000);
                calibrationCounter = 8;
                // show direction
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        calibrationDirection.setText(R.string.calibration_direction_2);
                    }
                });
                Thread.sleep(5000);
                calibrationCounter = CALIBRATION_STEP_MINMAX;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        calibrationDirection.setText(R.string.calibration_direction_3);
                    }
                });
                Thread.sleep(15000);
                calibrationCounter = CALIBRATION_STEP_NORMAL;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        calibrationDirection.setText(R.string.calibration_direction_4);
                    }
                });
                Thread.sleep(5000);
                calibrationCounter = CALIBRATION_STEP_NORMAL - 1;
                // 計算
                calcNormalPosition();
                calibrationCounter = 0;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        calibrationDirection.setText(R.string.calibration_direction_5);
                        view.removeView(calibrationDirection);
                    }
                });
                showCalibratedValue();
                finishCalibration();
            } catch (Exception calibrationException) {
                calibrationException.printStackTrace();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        calibrationDirection.setText(R.string.calibration_direction_6);
                    }
                });
            }
        }
    });

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        initBroadcastReceiver();
        initServiceConnection();

        mHUDHeadingManager = (HUDHeadingManager) HUDOS.getHUDService(HUDOS.HUD_HEADING_SERVICE);

        view = (RelativeLayout) findViewById(R.id.container);
        listView = (ListView) findViewById(R.id.list);
        calibrationDirection = new TextView(this);

        deviceList = new ArrayList<ARDiscoveryDeviceService>();
        deviceNameList = new String[]{};

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, android.R.id.text1, deviceNameList);


        // Assign adapter to ListView
        listView.setAdapter(adapter);

        // ListView Item Click Listener
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener()
        {

            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id)
            {
                if (!isCalibrated) return;
                ARDiscoveryDeviceService service = deviceList.get(position);

                Intent intent = new Intent(MainActivity.this, PilotingActivity.class);
                intent.putExtra(PilotingActivity.EXTRA_DEVICE_SERVICE, service);


                startActivity(intent);
            }

        });
    }

    public void onStart() {
        super.onStart();
        mHUDHeadingManager.register(this);
    }

    public void onStop() {
        super.onStop();
        mHUDHeadingManager.unregister(this);
    }

    private void initServices()
    {
        if (discoveryServiceBinder == null)
        {
            Intent i = new Intent(getApplicationContext(), ARDiscoveryService.class);
            getApplicationContext().bindService(i, ardiscoveryServiceConnection, Context.BIND_AUTO_CREATE);
        }
        else
        {
            ardiscoveryService = ((ARDiscoveryService.LocalBinder) discoveryServiceBinder).getService();
            ardiscoveryServiceBound = true;

            ardiscoveryService.start();
        }
    }

    private void closeServices()
    {
        Log.d(TAG, "closeServices ...");

        if (ardiscoveryServiceBound)
        {
            new Thread(new Runnable() {
                @Override
                public void run()
                {
                    ardiscoveryService.stop();

                    getApplicationContext().unbindService(ardiscoveryServiceConnection);
                    ardiscoveryServiceBound = false;
                    discoveryServiceBinder = null;
                    ardiscoveryService = null;
                }
            }).start();
        }
    }

    private void initBroadcastReceiver()
    {
        ardiscoveryServicesDevicesListUpdatedReceiver = new ARDiscoveryServicesDevicesListUpdatedReceiver(this);
    }

    private void initServiceConnection()
    {
        ardiscoveryServiceConnection = new ServiceConnection()
        {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service)
            {
                discoveryServiceBinder = service;
                ardiscoveryService = ((ARDiscoveryService.LocalBinder) service).getService();
                ardiscoveryServiceBound = true;

                ardiscoveryService.start();
            }

            @Override
            public void onServiceDisconnected(ComponentName name)
            {
                ardiscoveryService = null;
                ardiscoveryServiceBound = false;
            }
        };
    }

    private void registerReceivers()
    {
        LocalBroadcastManager localBroadcastMgr = LocalBroadcastManager.getInstance(getApplicationContext());
        localBroadcastMgr.registerReceiver(ardiscoveryServicesDevicesListUpdatedReceiver, new IntentFilter(ARDiscoveryService.kARDiscoveryServiceNotificationServicesDevicesListUpdated));

    }

    private void unregisterReceivers()
    {
        LocalBroadcastManager localBroadcastMgr = LocalBroadcastManager.getInstance(getApplicationContext());
        localBroadcastMgr.unregisterReceiver(ardiscoveryServicesDevicesListUpdatedReceiver);
    }


    @Override
    public void onResume() {
        super.onResume();

        Log.d(TAG, "onResume ...");

        onServicesDevicesListUpdated();

        registerReceivers();

        initServices();
    }

    @Override
    public void onPause() {
        Log.d(TAG, "onPause ...");

        unregisterReceivers();
        closeServices();

        super.onPause();
    }

    @Override
    public void onServicesDevicesListUpdated()
    {
        Log.d(TAG, "onServicesDevicesListUpdated ...");

        List<ARDiscoveryDeviceService> list;

        if (ardiscoveryService != null)
        {
            list = ardiscoveryService.getDeviceServicesArray();

            deviceList = new ArrayList<ARDiscoveryDeviceService> ();
            List<String> deviceNames = new ArrayList<String>();

            if(list != null)
            {
                for (ARDiscoveryDeviceService service : list)
                {
                    Log.e(TAG, "service :  "+ service + " name = " + service.getName());
                    ARDISCOVERY_PRODUCT_ENUM product = ARDiscoveryService.getProductFromProductID(service.getProductID());
                    Log.e(TAG, "product :  "+ product);
                    // only display Bebop drones
                    if (ARDISCOVERY_PRODUCT_ENUM.ARDISCOVERY_PRODUCT_ARDRONE.equals(product))
                    {
                        deviceList.add(service);
                        deviceNames.add(service.getName());
                    }
                }
            }

            deviceNameList = deviceNames.toArray(new String[deviceNames.size()]);

            ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, android.R.id.text1, deviceNameList);

            // Assign adapter to ListView
            listView.setAdapter(adapter);
        }

    }

    /**
     * 頭の動き検知
     * @param yaw
     * @param pitch
     * @param roll
     */
    @Override
    public void onHeadLocation(float yaw, float pitch, float roll) {
        System.out.println("headLocation:" + yaw + " " + pitch + " " + roll);
        if (minYaw == 10000) {
            minYaw = maxYaw = yaw;
        }
        if (isInCalibration) {
            switch (calibrationCounter) {
                case CALIBRATION_STEP_MINMAX:
                    if (yaw > maxYaw) maxYaw = yaw;
                    if (yaw < minYaw) minYaw = yaw;
                    if (pitch > maxPitch) maxPitch = pitch;
                    if (pitch < minPitch) minPitch = pitch;
                    if (roll > maxRoll) maxRoll = roll;
                    if (roll < minRoll) minRoll = roll;
                    break;
                case CALIBRATION_STEP_NORMAL:
                    yawBuf.add(yaw);
                    pitchBuf.add(pitch);
                    rollBuf.add(roll);
                    break;
                default: break;
            }
        } else {
            if (!isCalibrated) {
                // Calibration
                doCalibration();
            }
        }
    }

    /**
     * キャリブレーション実施
     */
    private void doCalibration() {
        if (calibrationCounter == 10 && !calibrationThread.isAlive()) {
            calibrationThread.start();
        }
    }

    private void finishCalibration() {
        isInCalibration = false;
        isCalibrated = true;
        mHUDHeadingManager.unregister(MainActivity.this);

    }

    /**
     * キャリブレーション結果表示
     */
    private void showCalibratedValue() {
        System.out.println("Yaw:[" + minYaw + " < " + norYaw + " < " + maxYaw
                        + "Pitch:[" + minPitch + " < " + norPitch + " < " + maxPitch
                        + "Roll:[" + minRoll + " < " + norRoll + " < " + maxRoll
        );
    }

    private void calcNormalPosition() throws Exception {
        List<Float> yaw, pitch, roll;
        float a = 0, b = 0, c = 0;
        int yawLen = yawBuf.size();
        int pitchLen = pitchBuf.size();
        int rollLen = rollBuf.size();
        if (yawLen < 50 || pitchLen < 50 || rollLen < 50) {
            throw new Exception("[Error on Calibration]lack of data.");
        }
        // 上下１０％カット
        int yawCutoff = yawLen / 10;
        int pitchCutoff = pitchLen / 10;
        int rollCutoff = rollLen / 10;
        yaw = yawBuf.subList(yawCutoff, yawLen - yawCutoff - 1);
        pitch = pitchBuf.subList(pitchCutoff, pitchLen - pitchCutoff - 1);
        roll = rollBuf.subList(rollCutoff, rollLen - rollCutoff - 1);
        for (float y : yaw) {
            a += y;
        }
        norYaw = a / (float) yaw.size();
        for (float p : pitch) {
            b += p;
        }
        norPitch = b / (float) pitch.size();
        for (float r : roll) {
            c += r;
        }
        norRoll = c / (float) roll.size();
    }
}
