package org.yash.hostelwifi;
import android.content.*;import android.os.Build;
public final class BootReceiver extends BroadcastReceiver {public void onReceive(Context c,Intent i){if(Config.p(c).getBoolean("auto",false)){Intent s=new Intent(c,PortalService.class);try{if(Build.VERSION.SDK_INT>=26)c.startForegroundService(s);else c.startService(s);}catch(Exception ignored){}}}}
