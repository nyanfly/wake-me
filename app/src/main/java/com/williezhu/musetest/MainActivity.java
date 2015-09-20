// much of this code is taken from muse example app, available at
// http://developer.choosemuse.com/android/getting-started-with-libmuse-android

package com.williezhu.musetest;

import android.app.Activity;
import android.os.Environment;
import android.support.v7.app.ActionBarActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
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

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MainActivity extends ActionBarActivity {

    FileOutputStream outputStream;

    Complex[] leftEarEeg, leftForeheadEeg, rightForeheadEeg, rightEarEeg;
    double[] sleepData;
    int bufferFill, dataFill;
    double mean, stdDev;

    boolean hasCalibrated = false;

    public void logToFile(String string) {
        try {
            outputStream.write((string + "\n").getBytes());
        } catch (IOException e) {
            // pass
        }
    }

    boolean headbandOn = false;

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
            Activity activity = activityRef.get();
            // UI thread is used here only because we need to update
            // TextView values. You don't have to use another thread, unless
            // you want to run disconnect() or connect() from connection packet
            // handler. In this case creating another thread is required.
            if (activity != null) {
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        TextView statusText =
                                (TextView) findViewById(R.id.con_status);
                        statusText.setText(status);
                    }
                });
            }
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
            final ArrayList<Double> data = p.getValues();

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
            Activity activity = activityRef.get();
            if (activity != null) {
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        TextView battery = (TextView) findViewById(R.id.battery);
                        battery.setText(String.format("%2.1f",
                                data.get(Battery.CHARGE_PERCENTAGE_REMAINING.ordinal())));
                    }
                });
            }
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

                if (dataFill < 60 && headbandOn && !hasCalibrated) {
                    sleepData[dataFill] = alphaBetaRatio;
                    Log.d("data", "collecting: " + alphaBetaRatio);
                    dataFill++;
                } else if (dataFill == 60 & !hasCalibrated) {
                    Statistics stats = new Statistics(sleepData);
                    mean = stats.getMean();
                    stdDev = stats.getStdDev();
                    Log.d("data", "calibrated!");
                    hasCalibrated = true;

                    // use running averages from now on with 5 inputs
                    for (int i = 0; i < 5; i++) {
                        sleepData[i] = mean;
                    }

                    dataFill = 0;
                }

                if (hasCalibrated) {
                    dataFill = (dataFill + 1) % 5;
                    sleepData[dataFill] = alphaBetaRatio;

                    Statistics stats = new Statistics(Arrays.copyOfRange(sleepData, 0, 5));

                    if (stats.getMean() > (mean + 2.5 * stdDev)) {
                        Log.d("SLEEP", "YOU SLEEPING BRO");
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

        sleepData = new double[60];

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
    }

    public void onClick(View v) {
        Spinner musesSpinner = (Spinner) findViewById(R.id.muses_spinner);
        if (v.getId() == R.id.refresh) {
            MuseManager.refreshPairedMuses();
            List<Muse> pairedMuses = MuseManager.getPairedMuses();
            List<String> spinnerItems = new ArrayList<String>();
            for (Muse m: pairedMuses) {
                String dev_id = m.getName() + "-" + m.getMacAddress();
                Log.i("Muse Headband", dev_id);
                spinnerItems.add(dev_id);
            }
            ArrayAdapter<String> adapterArray = new ArrayAdapter<String> (
                    this, android.R.layout.simple_spinner_item, spinnerItems);
            musesSpinner.setAdapter(adapterArray);
        }
        else if (v.getId() == R.id.connect) {
            List<Muse> pairedMuses = MuseManager.getPairedMuses();
            if (pairedMuses.size() < 1 ||
                    musesSpinner.getAdapter().getCount() < 1) {
                Log.w("Muse Headband", "There is nothing to connect to");
            }
            else {
                muse = pairedMuses.get(musesSpinner.getSelectedItemPosition());
                ConnectionState state = muse.getConnectionState();
                if (state == ConnectionState.CONNECTED ||
                        state == ConnectionState.CONNECTING) {
                    Log.w("Muse Headband",
                            "doesn't make sense to connect second time to the same muse");
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
        else if (v.getId() == R.id.disconnect) {
            if (muse != null) {
                /**
                 * true flag will force libmuse to unregister all listeners,
                 * BUT AFTER disconnecting and sending disconnection event.
                 * If you don't want to receive disconnection event (for ex.
                 * you call disconnect when application is closed), then
                 * unregister listeners first and then call disconnect:
                 * muse.unregisterAllListeners();
                 * muse.disconnect(false);
                 */
                muse.disconnect(true);
                fileWriter.addAnnotationString(1, "Disconnect clicked");
                fileWriter.flush();
                fileWriter.close();
            }
        }
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

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onDestroy() {
        try {
            outputStream.close();
        } catch (IOException e) {
            // again pray
        }

        super.onDestroy();
    }
}
