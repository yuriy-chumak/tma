package com.iaaa;

import java.io.*;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.*;
import java.util.*;

import org.json.JSONObject;

import com.iaaa.gps.NMEA;
import com.iaaa.gps.nmea.*;

import android.app.*;
import android.content.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.location.*;
import android.os.*;
import android.os.*;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.util.Log;
import android.widget.Toast;

import com.track.my.ass.Preferences;
import com.track.my.ass.R;
import com.iaaa.gps.nmea.GGA;

@SuppressWarnings("deprecation")
public class Gps extends Service
implements
	LocationListener,
	GpsStatus.Listener,
	OnNmeaMessageListener
{
	final static String TAG = "Gps";
    final static String NOTIFICATION_TITLE = "Track My Ass";

	// Binder
	public final class LocalBinder extends Binder {
		public Gps getService() {
			Log.i(TAG, "getService()");
			return Gps.this;
		}
	}
	private final IBinder localBinder = new LocalBinder();

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		Log.i(TAG, "onStartCommand(" + startId + "), " + intent);
		return START_STICKY;
	}
	@Override
	public IBinder onBind(Intent arg0) {
		Log.i(TAG, "onBind(" + arg0 + ")");
		return localBinder;
	}

	// Service
	static boolean alive = false;
	public static void Shutdown()
	{
		Log.i(TAG, "Shutdown()");
	}

	private GnssStatus.Callback gnss;
	private LocationManager getLocationManager()
	{
		return (LocationManager) getApplicationContext().getSystemService(Context.LOCATION_SERVICE);
	}

	// Просыпаемся по таймеру, проверяем координаты, отсылаем их на сервер,
	//	и можем снова заспать.
	private WakeLock wl = null;
	public static class AlarmReceiver extends BroadcastReceiver {
		protected static AlarmManager alarmManager = null;
		protected static final String INTERVAL = "60000"; // 1 minute
		protected static final String FIRST_RUN = "5000"; // 5 seconds

		protected static PendingIntent pendingIntent;
		protected static long timestamp = System.currentTimeMillis();

		protected static String session = null;

		@Override
		public void onReceive(Context context, Intent intent) {
			Log.i(TAG, "Timed alarm onReceive()");
			String sleep_for = Preferences.getString("tracking_interval", INTERVAL);
			alarmManager.set(AlarmManager.RTC_WAKEUP,
					System.currentTimeMillis() + Integer.valueOf(sleep_for), pendingIntent);

			new TrackerTask().execute(context);
		}

		public static void Register(Context context) {
			Intent intent = new Intent(context, AlarmReceiver.class);
			pendingIntent = PendingIntent.getBroadcast(context,
					0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
			alarmManager = (AlarmManager) context.getSystemService(ALARM_SERVICE);
			alarmManager.set(AlarmManager.RTC_WAKEUP,
					System.currentTimeMillis() + Integer.valueOf(FIRST_RUN), pendingIntent);
		}

		public static void Shutdown() {
			if (alarmManager != null)
				if (pendingIntent != null)
					alarmManager.cancel(pendingIntent);
		}

		static class TrackerTask extends AsyncTask<Context, Void, Void>
		{
			protected String backend_address;
			@Override
			protected Void doInBackground(Context... contexts)
			{
				// send GPS location
				if (!Preferences.getBoolean("perform_tracking"))
					return null; // no tracking enabled
				backend_address = Preferences.getString("backend_address");
				if (backend_address == null) // no backend address available
					return null;

				Context context = contexts[0];

				// todo: change to state machine
				if (session == null) {
					int code = Ping();
					if (code == 401) { // server available, do login
						code = Login();
						if (code != 200) { // invalid login/password
							Log.e(TAG, "send: invalid login/password");
							return null;
						}
					}
				}
				if (session == null)
					return null;

				// collect and send gps data
				PutLocation();
				return null;
			}

			protected int Ping()
			{
				HttpURLConnection input = null;
				try {
					input = (HttpURLConnection) new URL(backend_address + "/ping").openConnection();
					input.connect();
			
					Log.i(TAG, "input.getResponseCode() = " + input.getResponseCode());
					return input.getResponseCode();
				}
				catch (Exception ex) {
					Log.e(TAG, "No Ping: " + ex.toString());
				}
				finally {
					if (input != null)
						input.disconnect();
				}
				return 0;
			}

			protected int Login()
			{
				int code = 0;
				HttpURLConnection connection = null;

				try {
					JSONObject postData = new JSONObject();
					postData.put("login", Preferences.getString("backend_login"));
					postData.put("password", Preferences.getString("backend_password"));
					byte[] data = postData.toString().getBytes();

					connection = (HttpURLConnection) new URL(backend_address + "/login").openConnection();
					connection.setDoInput(true);
					connection.setRequestMethod("POST");
					connection.setDoOutput(true);
					connection.setFixedLengthStreamingMode(data.length);

					connection.setRequestProperty("Content-Type", "application/json");
					OutputStream out = new BufferedOutputStream(connection.getOutputStream());
					out.write(data);
					out.flush();

					// send data
					connection.connect();
					code = connection.getResponseCode();

					Log.i(TAG, "Login " + code);
					if (code == 200) {
						JSONObject response = new JSONObject(
							new Scanner(connection.getInputStream()).useDelimiter("\\A").next());
						Log.i(TAG, "Response: " + response.toString());
						
						String s = response.getString("session");
						if (s != null)
							session = s;
						return code;
					}
				}
				catch (Exception ex) {
					Log.e(TAG, "Login failed: " + ex.toString());
				}
				finally {
					if (connection != null)
						connection.disconnect();
				}
				return code;
			}

			protected int PutLocation()
			{
				int code = 0;
				HttpURLConnection connection = null;

				try {
					JSONObject postData = new JSONObject();
					postData.put("lat", GGA.Latitude);
					postData.put("lon", GGA.Longitude);
					postData.put("utc", GGA.Time);
					byte[] data = postData.toString().getBytes();

					connection = (HttpURLConnection) new URL(backend_address + "/location").openConnection();
					connection.setRequestMethod("PUT");
					connection.setDoOutput(true);
					connection.setFixedLengthStreamingMode(data.length);

					connection.setRequestProperty("X-TmaGps-SID", session);

					connection.setRequestProperty("Content-Type", "application/json");
					OutputStream out = new BufferedOutputStream(connection.getOutputStream());
					out.write(data);
					out.flush();

					// send data
					connection.connect();
					code = connection.getResponseCode();

					Log.i(TAG, "Put Location " + code);
				}
				catch (Exception ex) {
					Log.e(TAG, "Put Location failed: " + ex.toString());
				}
				finally {
					if (connection != null)
						connection.disconnect();
				}
				return code;
			}
		}
	}

	protected BroadcastReceiver broadcastReceiver = null;

	@Override
	public void onCreate() {
		super.onCreate();

		Log.i(TAG, "onCreate");
		// PowerManager pm = (PowerManager)getApplicationContext().getSystemService(Context.POWER_SERVICE);
		// wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
		// wl.acquire();

        Notification notification = getNotification("Gps Service Started");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

		// wake up every N seconds(minutes), receive GPS data, send it, and sleep again
		AlarmReceiver.Register(this);

		// android 11+ wrapper
		final GpsStatus.Listener self = this;
		gnss = new GnssStatus.Callback() {
			@Override
			public void onStarted() {
				super.onStarted();
				self.onGpsStatusChanged(GpsStatus.GPS_EVENT_STARTED);
			}
			@Override
			public void onStopped() {
				super.onStopped();
				self.onGpsStatusChanged(GpsStatus.GPS_EVENT_STOPPED);
			}
			@Override
			public void onFirstFix(int ttffMillis) {
				super.onFirstFix(ttffMillis);
				self.onGpsStatusChanged(GpsStatus.GPS_EVENT_FIRST_FIX);
			}
			@Override
			public void onSatelliteStatusChanged(GnssStatus status) {
				super.onSatelliteStatusChanged(status);
				self.onGpsStatusChanged(GpsStatus.GPS_EVENT_SATELLITE_STATUS);
			}
		};

		LocationManager locationManager = getLocationManager();
		locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, this);
		locationManager.addNmeaListener(this);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            locationManager.addGpsStatusListener(this);
        } else { // Android 11+
        	locationManager.registerGnssStatusCallback(gnss);
		}
		locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0, 0, this);
		//locationManager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 0, 0, this);

		// ---------------------------------------
		// handle preferences
		if (Preferences.getBoolean("record_nmea"))
			Recorder.Record();
		// and track preferences change
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction("GPS_PREFERENCES_CHANGED");
		broadcastReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				Log.i(TAG, "Preferences changed");
				// dumping NMEA?
				if (Preferences.getBoolean("record_nmea")) {
					if (Recorder.isRecording() == false)
						Recorder.Record();
				}
				else if (Recorder.isRecording() == true)
						Recorder.Stop();
				// etc.
			}
		};
        registerReceiver(broadcastReceiver, intentFilter);

		// ok
        Toast.makeText(this, "GPS Service Started", Toast.LENGTH_LONG).show();
	}
    
    @Override
    public void onDestroy() {
    	super.onDestroy();
    	
		Log.i(TAG, "onDestroy");
		if (wl != null)
			if (wl.isHeld())
				wl.release();
		AlarmReceiver.Shutdown();

		LocationManager locationManager = getLocationManager();
		locationManager.removeNmeaListener(this);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
			locationManager.removeGpsStatusListener(this);
		else
        	locationManager.unregisterGnssStatusCallback(gnss);
		locationManager.removeUpdates(this);

		if (broadcastReceiver != null)
			unregisterReceiver(broadcastReceiver);

		RMC.Status = 'V';	// отметимся, что больше данных GPS нет
		
		Recorder.Stop(); // stop record if recording
		Toast.makeText(this, "Gps Service Stopped", Toast.LENGTH_LONG).show();
	}
    
	public static class Recorder
	{
		private static BufferedWriter dump = null;
		public static boolean isRecording()
		{
			return dump != null;
		}
    
		public static void Record()
		{
			if (dump != null)
				Stop();
			
			try {
				File storageDirectory = new File(Environment.getExternalStorageDirectory(), "/logs");
				storageDirectory.mkdirs();
				DateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd HH-mm-ss");
				String format = dateFormatter.format(Calendar.getInstance().getTime());
				File logFile = new File(storageDirectory,
						format + ".nmea.log"
				);
				dump = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(logFile), "utf-8"));
			}
			catch (Exception ex) {
				ex.printStackTrace();
			}
		}
		public static void Stop()
		{
			if (dump == null)
				return;
			
			try {
				dump.flush();
				dump.close();
			} catch (IOException ex) {
				ex.printStackTrace();
			}
			finally {
				dump = null;
			}
		}
		public static void Write(String message)
		{
			try {
				if (dump != null)
					dump.write(message);
			}
			catch (IOException ex) {
				// can't write. ok...
			}
		}
	}
    
	// raw NMEA message receiver:
    private void onNmea(String message, long timestamp)
    {
		if (Recorder.isRecording())
			Recorder.Write(message);
		NMEA.Type type = Static.onNmeaReceived(timestamp, message);
		if (type == NMEA.Type.RMC) {
			if (RMC.Status == 'A')
				updateNotification("GPS position status: Valid");
			else
				updateNotification("No GPS position calculated");
		}
    }
	
	@Override
	public void onNmeaMessage(String message, long timestamp) {
		onNmea(message, timestamp);
	}

	// <!-- GpsStatus.NmeaListener
	public void onProviderEnabled(String provider) {
		Log.v(TAG, "Enabled");
		if (Preferences.getBoolean("show_notifications"))
			Toast.makeText(getApplicationContext(), "GPS Enabled", Toast.LENGTH_SHORT).show();		
	}
	public void onProviderDisabled(String provider) {
		Log.v(TAG, "Disabled");
		if (Preferences.getBoolean("show_notifications"))
			Toast.makeText(getApplicationContext(), "GPS Disabled", Toast.LENGTH_LONG).show();
		
		RMC.Status = 'V';	// отметимся, что больше нету у нас данных GPS (хак)
		Static.Knock();
	}
	public void onLocationChanged(Location location) {
		// Log.v(TAG, "onLocationChanged");
	}
	public void onStatusChanged(String provider, int status, Bundle extras) {
		Log.v(TAG, "onStatusChanged");
	}
	// -->
	
	// <!-- GpsStatus.Listener -->
	@Override
	public void onGpsStatusChanged(int arg0) {
		// Log.v(TAG, "onGpsStatusChanged " + String.valueOf(arg0));
	}

	
	// << Static >>
	
	public static final void Subscribe(Subscriber subscriber)
	{
		Static.Subscribe(subscriber);
	}
	public static final void Unsubscribe(Subscriber subscriber)
	{
		Static.Unsubscribe(subscriber);
	}
	public static final void UnsubscribeAll()
	{
		Static.UnsubscribeAll();
	}
	
	// static NMEA receiver
	private static final class Static
	{
		static int Count = 0;

		final static NMEA.Type onNmeaReceived(long timestamp, String message)
		{
			// Log.v(TAG, message);
			Count++;
			NMEA.Type type = NMEA.Post(message);

			// do nothing if no subscribers
			synchronized(Subscribers) {
				if (Subscribers.size() == 0)
					return NMEA.Type.Unknown;
			}
			if (type == NMEA.Type.GGA)	// knock on "Global Positioning System Fix Data"
				Knock();
			return type;
		}
		
		final static void Knock() {
			synchronized(Subscribers) {
				for (Subscriber subscriber : Subscribers)
					subscriber.Knock();
			}
		}
		final static void Clear() {
			synchronized(Subscribers) {
				Subscribers.clear();
			}
		}
		
		final static List<Subscriber> Subscribers = new ArrayList<Subscriber>(); 
		final static void Subscribe(Subscriber subscriber) {
			synchronized(Subscribers) {
				Subscribers.add(subscriber);
			}
		}
		final static void Unsubscribe(Subscriber subscriber) {
			synchronized(Subscribers) {
				Subscribers.remove(subscriber);
			}
		}
		final static void UnsubscribeAll() {
			synchronized(Subscribers) {
				Subscribers.clear();
			}
		}
	}
	
	// DEBUG purposes
	static
	{
		final File debugSource = new File(Environment.getExternalStorageDirectory() + "/aGps/DEBUG.NMEA");
		if (debugSource.exists()/*"sdk".equals(Build.PRODUCT)*/) {
			// start the fake GPS stream
			new Thread() {
				@Override
				public void run()
				{
					BufferedReader source = null;
					try {
						source = new BufferedReader(new FileReader(debugSource));
						while (true) {
							Thread.sleep(1000 / 6);
							
							String message = source.readLine();
							if (message != null)
								Static.onNmeaReceived(0, message);
							else
								source.reset();	// restart from start
						}
					}
					catch (IOException e) {
						e.printStackTrace();
					}
					catch (InterruptedException e) {
						// do nothing..
					}
				}
			}.start();
		}
	}

	// TEMP
    final int NOTIFICATION_ID = 1;
    protected Notification getNotification(String text) {
        // Intent intent = new Intent(this, Activity.class);
        // intent.putExtra("notificationID", NOTIFICATION_ID);
        // intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP |
        //     Intent.FLAG_ACTIVITY_SINGLE_TOP);
        // PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, 0);

		Intent intentStop = new Intent(this, StopServiceReceiver.class);
		PendingIntent stop = PendingIntent.getBroadcast(this, (int) System.currentTimeMillis(), intentStop, PendingIntent.FLAG_CANCEL_CURRENT);

        Notification.Builder notificationBuilder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel notificationChannel = new NotificationChannel("tma_lock_gps", "GPS Status",
                    NotificationManager.IMPORTANCE_DEFAULT);
            notificationChannel.setSound(null, null);
            NotificationManager notificationManager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            notificationManager.createNotificationChannel(notificationChannel);
            notificationBuilder = new Notification.Builder(this, "tma_lock_gps");
        } else {
            notificationBuilder = new Notification.Builder(this);
        }
        return notificationBuilder
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                // .setContentIntent(pendingIntent)
                .setContentTitle(NOTIFICATION_TITLE)
                .setOngoing(true)
                .setContentText(text)
                .setSound(null)
				.addAction(android.R.drawable.ic_notification_clear_all, "Stop", stop)
				.setVisibility(Notification.VISIBILITY_PUBLIC)
                .getNotification();
    }

    protected void updateNotification(String text) {
        Notification notification = getNotification(text);
        NotificationManager notificationManager =
                (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        notificationManager.notify(NOTIFICATION_ID, notification);
    }

	public static class StopServiceReceiver extends BroadcastReceiver {
		@Override
		public void onReceive(Context context, Intent intent) {
			Intent service = new Intent(context, Gps.class);
			context.stopService(service);
		}
	}	
}
