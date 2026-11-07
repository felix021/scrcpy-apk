package cn.felix021.scrcpy;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import java.util.Locale;

/** Resources are independent of the Activity, so changing language does not
 * recreate the video surface or retain a destroyed Activity in a worker. */
final class UiText {
    private static Context application;
    private static volatile Resources resources;
    private static String option="system",effective="";
    static synchronized void init(Context context,String language){application=context.getApplicationContext();option=language;effective="";refresh();}
    static synchronized String language(){return option;}
    static synchronized void setLanguage(String language){option=language;refresh();}
    static synchronized boolean refresh(){
        if(application==null)return false;
        String next=option.equals("system")?Resources.getSystem().getConfiguration().getLocales().get(0).getLanguage():option;
        next=next.equals("zh")?"zh":"en";
        if(next.equals(effective))return false;
        Configuration config=new Configuration(application.getResources().getConfiguration());config.setLocale(Locale.forLanguageTag(next));
        resources=application.createConfigurationContext(config).getResources();effective=next;return true;
    }
    static String text(int id){return resources.getString(id);}
    static String format(int id,Object...args){return resources.getString(id,args);}
    static String error(Throwable error){return "TEXT_TOO_LARGE".equals(error.getMessage())?text(R.string.text_is_limited_to_64_kb_per_message):error.getMessage();}
}
