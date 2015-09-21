// much of this code is taken from muse example app, available at
// http://developer.choosemuse.com/android/getting-started-with-libmuse-android

package com.williezhu.musetest;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.Dialog;
import android.app.PendingIntent;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Vibrator;
import android.support.v7.app.ActionBarActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.NumberPicker;
import android.widget.RelativeLayout;
import android.widget.Spinner;
import android.widget.TextView;

import com.interaxon.libmuse.AnnotationData;
import com.interaxon.libmuse.Battery;
import com.interaxon.libmuse.ConnectionState;
import com.interaxon.libmuse.Eeg;
import com.interaxon.libmuse.LibMuseVersion;
import com.interaxon.libmuse.MessageType;
import com.interaxon.libmuse.Muse;
import com.interaxon.libmuse.MuseArtifactPacket;
import com.interaxon.libmuse.MuseConfiguration;
import com.interaxon.libmuse.MuseConnectionListener;
import com.interaxon.libmuse.MuseConnectionPacket;
import com.interaxon.libmuse.MuseDataListener;
import com.interaxon.libmuse.MuseDataPacket;
import com.interaxon.libmuse.MuseDataPacketType;
import com.interaxon.libmuse.MuseFileFactory;
import com.interaxon.libmuse.MuseFileReader;
import com.interaxon.libmuse.MuseFileWriter;
import com.interaxon.libmuse.MuseManager;
import com.interaxon.libmuse.MusePreset;
import com.interaxon.libmuse.MuseVersion;

import org.w3c.dom.Text;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MainActivity extends ActionBarActivity {

    FileOutputStream outputStream;

    Complex[] leftEarEeg, leftForeheadEeg, rightForeheadEeg, rightEarEeg;
    double[] sleepData;
    int bufferFill, dataFill;
    double mean, stdDev;

    boolean hasCalibrated = false;

    boolean headbandOn = false;

    int state = 0;  // is staying awake
    int violations = 0;

    Timer connectTimer;

    class ConnectionListener extends MuseConnectionListener {

        final WeakReference<Activity> activityRef;

        ConnectionListener(final WeakReference<Activity> activityRef) {
            this.activityRef = activityRef;
        }

        @Override
        public void receiveMuseConnectionPacket(MuseConnectionPacket p) {
            final ConnectionState current = p.getCurrentConnectionState();
            final String status = p.getPreviousConnectionState().toString() +
                    " -> " + current;
            final String full = "Muse " + p.getSource().getMacAddress() +
                    " " + status;
            Log.i("Muse Headband", full);
        }
    }

    /**
     * Data listener will be registered to listen for: Accelerometer,
     * Eeg and Relative Alpha bandpower packets. In all cases we will
     * update UI with new values.
     * We also will log message if Artifact packets contains "blink" flag.
     * DataListener methods will be called from execution thread. If you are
     * implementing "serious" processing algorithms inside those listeners,
     * consider to create another thread.
     */
    class DataListener extends MuseDataListener {

        final WeakReference<Activity> activityRef;
        private MuseFileWriter fileWriter;

        DataListener(final WeakReference<Activity> activityRef) {
            this.activityRef = activityRef;
        }

        @Override
        public void receiveMuseDataPacket(MuseDataPacket p) {
            switch (p.getPacketType()) {
                case BATTERY:
                    updateBattery(p.getValues());
                    break;
                case EEG:
                    updateEeg(p.getValues());
                    break;
                default:
                    break;
            }
        }

        @Override
        public void receiveMuseArtifactPacket(MuseArtifactPacket p) {
            if (p.getHeadbandOn() && !headbandOn) {
                Log.i("Artifacts", "headband on!");
                headbandOn = true;
            } else if (!p.getHeadbandOn() && headbandOn) {
                Log.i("Artifacts", "headband off!");
                headbandOn = false;
            }
        }

        private void updateBattery(final ArrayList<Double> data) {
            Log.d("Battery", String.format("%6.2f", data.get(Battery.CHARGE_PERCENTAGE_REMAINING.ordinal())));
        }

        private void updateEeg(final ArrayList<Double> data) {
            if (bufferFill <= 511) {
                leftEarEeg[bufferFill] = new Complex(bufferFill, data.get(Eeg.TP9.ordinal()));
                leftForeheadEeg[bufferFill] = new Complex(bufferFill, data.get(Eeg.FP1.ordinal()));
                rightForeheadEeg[bufferFill] = new Complex(bufferFill, data.get(Eeg.FP2.ordinal()));
                rightEarEeg[bufferFill] = new Complex(bufferFill, data.get(Eeg.TP10.ordinal()));
                bufferFill++;
            }

            if (bufferFill == 512) {
                Complex[] y1 = FFT.fft(leftEarEeg);
                Complex[] y2 = FFT.fft(leftForeheadEeg);
                Complex[] y3 = FFT.fft(rightForeheadEeg);
                Complex[] y4 = FFT.fft(rightEarEeg);
                double[] z1 = FFT.power(y1, 512);
                double[] z2 = FFT.power(y2, 512);
                double[] z3 = FFT.power(y3, 512);
                double[] z4 = FFT.power(y4, 512);
                double[] wave1 = FFT.wakeme(z1);
                double[] wave2 = FFT.wakeme(z2);
                double[] wave3 = FFT.wakeme(z3);
                double[] wave4 = FFT.wakeme(z4);

                double[] frequencyStrengths = new double[5];

                for (int i = 0; i < wave1.length; i++) {
                    frequencyStrengths[i] = (wave1[i] / 0.25) + (wave2[i] / 0.25) + (wave3[i] / 0.25) + (wave4[i] / 0.25);
                }

                double alphaBetaRatio = frequencyStrengths[2] / frequencyStrengths[3];

                if (dataFill < 10 && headbandOn && !hasCalibrated) {
                    if (dataFill == 0) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                TextView textView = (TextView) findViewById(R.id.text_view);
                                textView.setText(getResources().getString(R.string.calibrating));
                            }
                        });
                    }

                    sleepData[dataFill] = alphaBetaRatio;
                    Log.d("data", "collecting: " + alphaBetaRatio);
                    dataFill++;
                } else if (dataFill == 10 & !hasCalibrated) {
                    Statistics stats = new Statistics(sleepData);
                    mean = stats.getMean();
                    stdDev = stats.getStdDev();
                    Log.d("data", "calibrated!");
                    hasCalibrated = true;

                    // use running averages from now on with 5 inputs
                    for (int i = 0; i < 5; i++) {
                        sleepData[i] = mean;
                    }

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            ImageButton imageButton = (ImageButton) findViewById(R.id.button);
                            imageButton.setBackgroundResource(R.drawable.teacher44);
                            TextView textView = (TextView) findViewById(R.id.text_view);
                            textView.setText(getResources().getString(R.string.learning));
                        }
                    });

                    dataFill = 0;
                }

                if (hasCalibrated && headbandOn) {
                    dataFill = (dataFill + 1) % 5;
                    sleepData[dataFill] = alphaBetaRatio;

                    Statistics stats = new Statistics(Arrays.copyOfRange(sleepData, 0, 5));

                    Log.d("data", "Got: " + stats.getMean() + " Threshold: " + (mean + 2.5 * stdDev));
                    if (stats.getMean() > (mean + 2.5 * stdDev)) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                ImageButton imageButton = (ImageButton) findViewById(R.id.button);    // and change background
                                imageButton.setBackgroundResource(R.drawable.exclamation2);
                                TextView textView = (TextView) findViewById(R.id.text_view);
                                textView.setText(getResources().getString(R.string.falling_asleep));
                                RelativeLayout relativeLayout = (RelativeLayout) findViewById(R.id.relative_layout);
                                relativeLayout.setBackgroundColor(getResources().getColor(R.color.accent2));
                            }
                        });

                        Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                        v.vibrate(2000);

                        final Handler handler = new Handler();
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                goBackToClass();
                            }
                        }, 2500);

                        violations++;

                        if (violations > 3) {
                            AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
                            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0);
                        }

                    }
                }

                bufferFill = 0;
            }
        }

        public void setFileWriter(MuseFileWriter fileWriter) {
            this.fileWriter  = fileWriter;
        }
    }

    private Muse muse = null;
    private ConnectionListener connectionListener = null;
    private DataListener dataListener = null;
    private boolean dataTransmission = true;
    private MuseFileWriter fileWriter = null;

    public MainActivity() {
        // Create listeners and pass reference to activity to them
        WeakReference<Activity> weakActivity =
                new WeakReference<Activity>(this);

        connectionListener = new ConnectionListener(weakActivity);
        dataListener = new DataListener(weakActivity);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        leftEarEeg = new Complex[512];
        leftForeheadEeg = new Complex[512];
        rightForeheadEeg = new Complex[512];
        rightEarEeg = new Complex[512];

        sleepData = new double[10];

        bufferFill = 0;
        dataFill = 0;

        File dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        fileWriter = MuseFileFactory.getMuseFileWriter(
                new File(dir, "new_muse_file.muse"));
        Log.i("Muse Headband", "libmuse version=" + LibMuseVersion.SDK_VERSION);
        fileWriter.addAnnotationString(1, "MainActivity onCreate");
        dataListener.setFileWriter(fileWriter);

        File sdCard = Environment.getExternalStorageDirectory();
        File file = new File(sdCard.getAbsolutePath(), "this_shit" + Long.valueOf(System.currentTimeMillis()));
        try {
            outputStream = new FileOutputStream(file);
        } catch (FileNotFoundException e) {
            // shit
        }

        TimerTask connectTask = new TimerTask() {
            @Override
            public void run() {
                MuseManager.refreshPairedMuses();
                List<Muse> pairedMuses = MuseManager.getPairedMuses();
                if (pairedMuses.size() < 1) {
                    Log.w("Muse Headband", "There is nothing to connect to");
                }
                else {
                    muse = pairedMuses.get(0);
                    ConnectionState state = muse.getConnectionState();
//                    if (state == ConnectionState.CONNECTED || state == ConnectionState.CONNECTING) {
                    if (state == ConnectionState.CONNECTED) {
                        this.cancel();  // stop running

                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                ImageButton imageButton = (ImageButton) findViewById(R.id.button);    // and change background
                                imageButton.setBackgroundResource(R.drawable.boy50);
                                TextView textView = (TextView) findViewById(R.id.text_view);
                                textView.setText(getResources().getString(R.string.put_on_muse));

                                final Dialog d = new Dialog(MainActivity.this);
                                d.setTitle("Length of class:");
                                d.setContentView(R.layout.dialog);
                                Button b1 = (Button) d.findViewById(R.id.button1);
                                final NumberPicker np = (NumberPicker) d.findViewById(R.id.numberPicker1);
                                np.setMaxValue(100); // max value 100
                                np.setMinValue(0);   // min value 0
                                np.setWrapSelectorWheel(false);
                                b1.setOnClickListener(new View.OnClickListener()
                                {
                                    @Override
                                    public void onClick(View v) {
                                        Uri gmmIntentUri = Uri.parse("geo:0,0?q=coffee");
                                        Intent mapIntent = new Intent(Intent.ACTION_VIEW, gmmIntentUri);
                                        mapIntent.setPackage("com.google.android.apps.maps");
                                        if (mapIntent.resolveActivity(getPackageManager()) != null) {
                                            AlarmManager alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
                                            alarmManager.set(AlarmManager.ELAPSED_REALTIME, 1000 * 60 * np.getValue(), PendingIntent.getBroadcast(getBaseContext(), 1, mapIntent, PendingIntent.FLAG_ONE_SHOT));
                                        }
                                        d.dismiss();
                                    }
                                });
                                d.show();
                            }
                        });

                        return;
                    }

                    configureLibrary();
                    fileWriter.open();
                    fileWriter.addAnnotationString(1, "Connect clicked");
                    /**
                     * In most cases libmuse native library takes care about
                     * exceptions and recovery mechanism, but native code still
                     * may throw in some unexpected situations (like bad bluetooth
                     * connection). Print all exceptions here.
                     */

                    try {
                        muse.runAsynchronously();
                    } catch (Exception e) {
                        Log.e("Muse Headband", e.toString());
                    }
                }
            }
        };

        connectTimer = new Timer();

        connectTimer.schedule(connectTask, 500, 500);

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                ImageButton imageButton = (ImageButton) findViewById(R.id.button);
                imageButton.setBackgroundResource(R.drawable.blueetooth);
                RelativeLayout relativeLayout = (RelativeLayout) findViewById(R.id.relative_layout);
                relativeLayout.setBackgroundColor(getResources().getColor(R.color.primary));
                TextView textView = (TextView) findViewById(R.id.text_view);
                textView.setText(getResources().getString(R.string.connecting));
            }
        });
    }

    private void goBackToClass() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                ImageButton imageButton = (ImageButton) findViewById(R.id.button);
                imageButton.setBackgroundResource(R.drawable.teacher44);
                RelativeLayout relativeLayout = (RelativeLayout) findViewById(R.id.relative_layout);
                relativeLayout.setBackgroundColor(getResources().getColor(R.color.primary));
                TextView textView = (TextView) findViewById(R.id.text_view);
                textView.setText(getResources().getString(R.string.learning));
            }
        });
    }

    private void configureLibrary() {
        muse.registerConnectionListener(connectionListener);
        muse.registerDataListener(dataListener,
                MuseDataPacketType.ACCELEROMETER);
        muse.registerDataListener(dataListener,
                MuseDataPacketType.EEG);
        muse.registerDataListener(dataListener,
                MuseDataPacketType.ALPHA_RELATIVE);
        muse.registerDataListener(dataListener,
                MuseDataPacketType.ARTIFACTS);
        muse.registerDataListener(dataListener,
                MuseDataPacketType.BATTERY);
        muse.setPreset(MusePreset.PRESET_14);
        muse.enableDataTransmission(dataTransmission);
    }

    public void onClick(View v) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                ImageButton imageButton = (ImageButton) findViewById(R.id.button);    // and change background
                imageButton.setBackgroundResource(R.drawable.exclamation2);
                TextView textView = (TextView) findViewById(R.id.text_view);
                textView.setText(getResources().getString(R.string.falling_asleep));
                RelativeLayout relativeLayout = (RelativeLayout) findViewById(R.id.relative_layout);
                relativeLayout.setBackgroundColor(getResources().getColor(R.color.accent2));
            }
        });

        Vibrator vi = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        vi.vibrate(2000);

        final Handler handler = new Handler();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                goBackToClass();
            }
        }, 2500);
    }

    @Override
    public void onDestroy() {
        try {
            outputStream.close();
        } catch (IOException e) {
            // again pray
        }

        muse.unregisterAllListeners();
        muse.disconnect(false);

        super.onDestroy();
    }
}
