package com.parrot.bebopdronepilotingnewapi;

import java.io.File;
import java.io.FileWriter;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;

/**
 * PID計算処理のテスト
 * Created by tsuyoshi on 2015/10/09.
 */
public class TestPid {
    static String datname = "dat";
    static FileWriter writer;
    static Thread testThread, pidThread;
    static Runnable testTask = new Runnable() {
        @Override
        public void run() {
            try {
                while (true) {
                    Thread.sleep(step);
                    yawInput = Math.sin(counter);
                    pitchInput = Math.sin(counter);
                    rollInput = Math.sin(counter);
                    double y = pidSynchronizer.getPIDYawControl(yawInput);
                    double p = pidSynchronizer.getPIDPitchControl(pitchInput);
                    double r = pidSynchronizer.getPIDRollControll(rollInput);
                    writer.append(yawTarget + "," + pitchTarget + "," + rollTarget + "," + y + "," + p + "," + r + "," + yawInput + "," + pitchInput + "," + rollInput);
                    counter += step; // 500msずつ
                    if (60000 > counter) {
                        writer.close();
                        System.out.println("テスト終了。ログを確認してください。");
                        break;
                    }
                }
            } catch (Exception allException) {
                System.out.println("テストタスクエラー："+allException.getMessage());
            }
        }
    };

    // test target
    static PIDSynchronizer pidSynchronizer;

    // test data
    static double yawInput = 0; // sin curve
    static double pitchInput = 0; // sin curve
    static double rollInput = 0; // sin curve

    // target data
    static double yawTarget = 1.0;
    static double pitchTarget = 1.0;
    static double rollTarget = 1.0;

    static long counter = 0;
    static int step = 500;

    public void setUp() throws Exception {
        try {
            // ログ書き出し準備
            writer = new FileWriter(new File(datname + "_" + System.currentTimeMillis()), true);
            // ヘッダー
            writer.write("target_yaw,target_pitch,target_roll,ope_yaw,ope_pitch,ope_roll,cur_yaw,cur_pitch,cur_rol¥n");
            // テスト対象
            pidSynchronizer = new PIDSynchronizer(null, null);
            pidSynchronizer.updateTargetYPRState(yawTarget, pitchTarget, rollTarget);
            pidThread = new Thread(pidSynchronizer);

            // テストループ
            testThread = new Thread(testTask);
        } catch (Exception all) {
            System.out.println("テストエラー:" + all.getMessage());
        }
    }

    public void testLog() throws Exception {
        System.out.println("テスト開始");
        if (pidThread != null) {
            pidThread.start();
        }
        if (testThread != null) {
            testThread.start();
        }
        assertThat(1, is(1));
    }
}
