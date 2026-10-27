package app.nepal.framextv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Native, D-pad-first catalogue. Only a title selection opens the provider embed. */
public final class MainActivity extends Activity {
    public static final String ACTION_REQUEST="app.nepal.framextv.action.REQUEST";
    public static final String EXTRA_REQUEST="theater_request";
    private static final int BACKGROUND=0xFF0A111C, SURFACE=0xFF162234;
    private static final int INK=0xFFF2F5FA, MUTED=0xFFBCC9D9, GOLD=0xFFD7C5A9;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final CatalogClient client=new CatalogClient();
    private final ExecutorService metadata=Executors.newSingleThreadExecutor(), images=Executors.newFixedThreadPool(2);
    private final List<Future<?>> imageTasks=new ArrayList<>();
    private final Set<String> loadingPosters=new HashSet<>();
    private final LruCache<String,Bitmap> posters=new LruCache<String,Bitmap>(6*1024*1024) {
        @Override protected int sizeOf(String key,Bitmap bitmap) { return bitmap.getAllocationByteCount(); }
    };
    private final List<CatalogClient.Item> allItems=new ArrayList<>(), visibleItems=new ArrayList<>();
    private final List<Button> filters=new ArrayList<>();
    private Future<?> activeRequest;
    private EditText query;
    private Button search;
    private TextView message;
    private ProgressBar progress;
    private GridView grid;
    private PosterAdapter adapter;
    private AlertDialog chooser;
    private int generation,operation,selectedPosition=AdapterView.INVALID_POSITION;
    private String selectedType="all",lastQuery="";
    private boolean stopped,busy,foreground,requestConsumed;
    private TheaterRequest pendingRequest,resultRequest;
    private String pendingRequestError;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN|WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(42),dp(26),dp(42),dp(12)); root.setBackgroundColor(BACKGROUND);
        TextView brand=text(getString(R.string.app_name),16,GOLD); brand.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        root.addView(brand,new LinearLayout.LayoutParams(-1,-2));
        TextView heading=text("What do you want to watch?",30,INK); heading.setPadding(0,dp(8),0,dp(16));
        root.addView(heading,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout row=new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        query=new EditText(this); query.setSingleLine(true); query.setTextSize(20); query.setTextColor(INK); query.setHintTextColor(MUTED);
        query.setHint("Search movies & shows"); query.setContentDescription("Search movies and TV shows");
        query.setFilters(new InputFilter[]{new InputFilter.LengthFilter(120)}); query.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH); query.setPadding(dp(18),0,dp(18),0);
        query.setBackground(surface(SURFACE,0xFF5A6D87,1,16));
        query.setOnFocusChangeListener((v,f)->query.setBackground(surface(SURFACE,f?GOLD:0xFF5A6D87,f?3:1,16)));
        query.setOnEditorActionListener((v,action,event)-> {
            if(action==EditorInfo.IME_ACTION_SEARCH||(event!=null&&event.getKeyCode()==KeyEvent.KEYCODE_ENTER&&event.getAction()==KeyEvent.ACTION_DOWN)) {searchNow();return true;}
            return false;
        });
        row.addView(query,new LinearLayout.LayoutParams(0,dp(56),1)); search=button("Search",this::searchNow);
        LinearLayout.LayoutParams sl=new LinearLayout.LayoutParams(dp(118),dp(56)); sl.leftMargin=dp(12); row.addView(search,sl);
        root.addView(row,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout filterRow=new LinearLayout(this); filterRow.setGravity(Gravity.CENTER_VERTICAL); filterRow.setPadding(0,dp(10),0,dp(8));
        for(String type:new String[]{"all","movie","tv"}) {
            Button filter=button("all".equals(type)?"All":"movie".equals(type)?"Movies":"TV shows",()->{selectedType=type;refreshFilters();showResults(false);});
            filter.setTag(type); filters.add(filter); LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(112),dp(48));lp.rightMargin=dp(8);filterRow.addView(filter,lp);
        }
        root.addView(filterRow,new LinearLayout.LayoutParams(-1,-2));
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setIndeterminate(true);progress.setVisibility(View.INVISIBLE);
        root.addView(progress,new LinearLayout.LayoutParams(-1,dp(3)));
        message=text("Search for a movie or show.",15,MUTED);message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);message.setPadding(0,dp(10),0,dp(10));
        root.addView(message,new LinearLayout.LayoutParams(-1,-2));
        grid=new GridView(this);grid.setNumColumns(4);grid.setHorizontalSpacing(dp(14));grid.setVerticalSpacing(dp(14));grid.setPadding(dp(4),dp(4),dp(4),dp(12));grid.setClipToPadding(false);
        grid.setDrawSelectorOnTop(true);grid.setSelector(surface(Color.TRANSPARENT,GOLD,3,16));grid.setVerticalScrollBarEnabled(false);
        adapter=new PosterAdapter();grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent,view,position,id)->{
            CatalogClient.Item item=adapter.getItem(position);
            if(item!=null){selectedPosition=position;select(item);}
        });
        root.addView(grid,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);refreshFilters();query.requestFocus();
        requestConsumed=state!=null&&state.getBoolean("theater_request_consumed",false);
        if(!requestConsumed)captureRequest(getIntent());
    }

    @Override protected void onStart() {super.onStart();stopped=false;}
    @Override protected void onResume(){super.onResume();foreground=true;runPendingRequest();}
    @Override protected void onPause(){foreground=false;super.onPause();}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);captureRequest(intent);}
    @Override protected void onSaveInstanceState(Bundle state){state.putBoolean("theater_request_consumed",requestConsumed);super.onSaveInstanceState(state);}

    private void captureRequest(Intent intent){
        if(intent==null||!ACTION_REQUEST.equals(intent.getAction()))return;
        // Every new request supersedes earlier work, including malformed input.
        // Its rejected predecessor must never finish by opening an old title.
        generation++;operation++;cancelWork();resultRequest=null;
        if(chooser!=null)chooser.dismiss();setBusy(false,"Preparing title request…");
        pendingRequest=null;pendingRequestError=null;requestConsumed=true;
        try{pendingRequest=TheaterRequest.decode(intent.getStringExtra(EXTRA_REQUEST));}
        catch(RuntimeException invalid){pendingRequestError="Use a title, optionally with a year, season and episode.";}
        finally{intent.removeExtra(EXTRA_REQUEST);intent.setAction(null);}
        if(foreground)runPendingRequest();
    }
    private void runPendingRequest(){
        if(!foreground||stopped)return;
        if(pendingRequestError!=null){message.setText(pendingRequestError);pendingRequestError=null;return;}
        TheaterRequest request=pendingRequest;pendingRequest=null;if(request==null)return;
        query.setText(request.title);selectedType=request.hasEpisode()?"tv":"all";refreshFilters();
        startSearch(request.title,request);
    }

    private void searchNow() {
        if(stopped)return;
        String value=query.getText().toString().trim();
        if(value.isEmpty()){message.setText("Enter a movie or show title.");query.requestFocus();return;}
        if(busy&&value.equals(lastQuery))return;
        startSearch(value,null);
    }
    private void startSearch(String value,TheaterRequest requestSpec){
        cancelWork();if(chooser!=null)chooser.dismiss();resultRequest=requestSpec;lastQuery=value;
        selectedPosition=AdapterView.INVALID_POSITION;allItems.clear();visibleItems.clear();adapter.notifyDataSetChanged();restoreGridSelection();generation++;
        final int epoch=generation,request=++operation;setBusy(true,"Searching…");
        InputMethodManager keyboard=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);if(keyboard!=null)keyboard.hideSoftInputFromWindow(query.getWindowToken(),0);
        activeRequest=metadata.submit(()->{
            try {List<CatalogClient.Item> result=client.search(value);main.post(()->{
                if(!accept(epoch,request))return;allItems.addAll(result);setBusy(false,"");showResults(true);
                if(requestSpec!=null&&requestSpec.play&&!visibleItems.isEmpty()){
                    String[] titles=new String[allItems.size()],years=new String[allItems.size()],types=new String[allItems.size()];
                    for(int index=0;index<allItems.size();index++){CatalogClient.Item item=allItems.get(index);titles[index]=item.title;years[index]=item.year;types[index]=item.type;}
                    int exact=requestSpec.uniqueMatch(titles,years,types);
                    if(exact>=0&&foreground&&visibleItems.contains(allItems.get(exact))){CatalogClient.Item item=allItems.get(exact);selectedPosition=visibleItems.indexOf(item);select(item);}
                    else message.setText("Choose the title to play.");
                }
            });}
            catch(IOException|RuntimeException failed){main.post(()->{if(accept(epoch,request))setBusy(false,"Search is unavailable. Try again.");});}
        });
    }

    private void refreshFilters() {
        for(Button filter:filters){boolean selected=selectedType.equals(filter.getTag());filter.setSelected(selected);filter.setTextColor(selected?GOLD:INK);}
    }
    private void showResults(boolean focus) {
        visibleItems.clear();for(CatalogClient.Item item:allItems)if("all".equals(selectedType)||selectedType.equals(item.type))visibleItems.add(item);
        if(focus)selectedPosition=AdapterView.INVALID_POSITION;
        adapter.notifyDataSetChanged();restoreGridSelection();
        if(!busy)message.setText(lastQuery.isEmpty()?"Search for a movie or show.":visibleItems.isEmpty()?"No matches. Try another title.":visibleItems.size()+(visibleItems.size()==1?" result":" results"));
        if(focus&&!visibleItems.isEmpty())grid.requestFocus();
    }
    private void restoreGridSelection(){
        int count=adapter.getCount();
        // GridView on API25 looks up the row ID synchronously in setSelection.
        // Empty/loading results have no row0; a filtered list can also shrink.
        selectedPosition=count==0?AdapterView.INVALID_POSITION:Math.max(0,Math.min(selectedPosition,count-1));
        grid.setSelection(selectedPosition);
    }
    private void select(CatalogClient.Item item) {
        if(busy||stopped||!foreground)return;
        if("movie".equals(item.type)){play(item,-1,-1);return;}
        if(resultRequest!=null&&resultRequest.hasEpisode()){openRequestedEpisode(item,resultRequest);return;}
        final int epoch=generation,request=++operation;setBusy(true,"Loading seasons…");
        activeRequest=metadata.submit(()->{
            try {List<CatalogClient.Season> seasons=client.seasons(item.id);main.post(()->{
                if(!accept(epoch,request))return;setBusy(false,"Choose a season");
                if(seasons.isEmpty()){message.setText("No seasons available for this show.");return;}
                String[] labels=new String[seasons.size()];for(int i=0;i<labels.length;i++)labels[i]=seasons.get(i).title;
                chooser=new AlertDialog.Builder(this).setTitle(item.title).setItems(labels,(dialog,position)->chooseEpisode(item,seasons.get(position))).setNegativeButton("Back",null).create();chooser.show();
            });}catch(IOException|RuntimeException failed){main.post(()->{if(accept(epoch,request))setBusy(false,"Seasons are unavailable. Try again.");});}
        });
    }
    private void openRequestedEpisode(CatalogClient.Item item,TheaterRequest requested){
        final int epoch=generation,request=++operation;setBusy(true,"Checking episode…");
        activeRequest=metadata.submit(()->{
            try{
                boolean seasonFound=false;for(CatalogClient.Season season:client.seasons(item.id))if(season.number==requested.season){seasonFound=true;break;}
                boolean episodeFound=false;
                if(seasonFound)for(CatalogClient.Episode episode:client.episodes(item.id,requested.season))if(episode.number==requested.episode){episodeFound=true;break;}
                final boolean available=episodeFound;
                main.post(()->{if(!accept(epoch,request))return;setBusy(false,available?"":"That episode is unavailable. Choose another title or search again.");
                    if(available){if(foreground)play(item,requested.season,requested.episode);else message.setText("Select the show to continue.");}
                });
            }catch(IOException|RuntimeException failed){main.post(()->{if(accept(epoch,request))setBusy(false,"Episode is unavailable. Try again.");});}
        });
    }
    private void chooseEpisode(CatalogClient.Item item,CatalogClient.Season season) {
        if(stopped)return;
        final int epoch=generation,request=++operation;setBusy(true,"Loading episodes…");
        activeRequest=metadata.submit(()->{
            try {List<CatalogClient.Episode> episodes=client.episodes(item.id,season.number);main.post(()->{
                if(!accept(epoch,request))return;setBusy(false,"Choose an episode");
                if(episodes.isEmpty()){message.setText("No episodes available for this season.");return;}
                String[] labels=new String[episodes.size()];for(int i=0;i<labels.length;i++)labels[i]=episodes.get(i).number+" · "+episodes.get(i).title;
                chooser=new AlertDialog.Builder(this).setTitle(item.title+" · "+season.title).setItems(labels,(dialog,position)->play(item,season.number,episodes.get(position).number)).setNegativeButton("Back",null).create();chooser.show();
            });}catch(IOException|RuntimeException failed){main.post(()->{if(accept(epoch,request))setBusy(false,"Episodes are unavailable. Try again.");});}
        });
    }
    private void play(CatalogClient.Item item,int season,int episode) {
        if(!foreground||stopped||isFinishing())return;
        startActivity(new Intent(this,EmbedPlayerActivity.class).putExtra(EmbedPlayerActivity.EXTRA_MEDIA_TYPE,item.type)
                .putExtra(EmbedPlayerActivity.EXTRA_TMDB_ID,item.id).putExtra(EmbedPlayerActivity.EXTRA_SEASON,season)
                .putExtra(EmbedPlayerActivity.EXTRA_EPISODE,episode).putExtra(EmbedPlayerActivity.EXTRA_TITLE,item.title));
    }
    private boolean accept(int epoch,int request){return !stopped&&generation==epoch&&operation==request&&!isFinishing();}
    private void setBusy(boolean value,String text){busy=value;progress.setVisibility(value?View.VISIBLE:View.INVISIBLE);grid.setEnabled(!value);message.setText(text);}
    private void cancelWork(){if(activeRequest!=null)activeRequest.cancel(true);for(Future<?> task:imageTasks)task.cancel(true);imageTasks.clear();loadingPosters.clear();client.cancelAll();}
    @Override protected void onStop(){stopped=true;generation++;operation++;cancelWork();setBusy(false,busy?"Search paused. Select Search to retry.":message.getText().toString());if(chooser!=null)chooser.dismiss();super.onStop();}
    @Override protected void onRestart(){super.onRestart();adapter.notifyDataSetChanged();restoreGridSelection();}
    @Override protected void onDestroy(){cancelWork();metadata.shutdownNow();images.shutdownNow();client.close();main.removeCallbacksAndMessages(null);posters.evictAll();super.onDestroy();}

    private final class PosterAdapter extends BaseAdapter {
        @Override public int getCount(){return visibleItems.size();}
        @Override public CatalogClient.Item getItem(int position){return position>=0&&position<getCount()?visibleItems.get(position):null;}
        @Override public long getItemId(int position){CatalogClient.Item item=getItem(position);return item==null?AdapterView.INVALID_ROW_ID:item.id;}
        @Override public View getView(int position,View old,ViewGroup parent){
            CatalogClient.Item item=visibleItems.get(position);LinearLayout card=new LinearLayout(MainActivity.this);
            card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(9),dp(9),dp(9),dp(10));card.setBackground(surface(SURFACE,0xFF28384E,1,16));
            card.setContentDescription(item.title+", "+item.year+", "+("tv".equals(item.type)?"TV show":"Movie"));
            ImageView poster=new ImageView(MainActivity.this);poster.setScaleType(ImageView.ScaleType.CENTER_CROP);poster.setImageResource(R.drawable.ic_framextv);poster.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            card.addView(poster,new LinearLayout.LayoutParams(-1,dp(150)));
            TextView title=text(item.title,16,INK);title.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));title.setMaxLines(2);title.setMinLines(2);title.setEllipsize(TextUtils.TruncateAt.END);title.setPadding(dp(3),dp(7),dp(3),0);card.addView(title,new LinearLayout.LayoutParams(-1,-2));
            TextView detail=text(("tv".equals(item.type)?"TV show":"Movie")+(item.year.isEmpty()?"":" · "+item.year),12,MUTED);detail.setPadding(dp(3),dp(3),dp(3),0);card.addView(detail,new LinearLayout.LayoutParams(-1,-2));
            loadPoster(poster,item.posterUrl);return card;
        }
    }
    private void loadPoster(ImageView view,String url) {
        if(url==null||url.isEmpty()||stopped)return;view.setTag(url);Bitmap cached=posters.get(url);if(cached!=null){view.setImageBitmap(cached);return;}
        imageTasks.removeIf(Future::isDone);
        if(imageTasks.size()>=32||!loadingPosters.add(url))return;final int epoch=generation;
        imageTasks.add(images.submit(()->{
            Bitmap image=null;
            try {byte[] bytes=client.poster(url);BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);
                if(bounds.outWidth>0&&bounds.outHeight>0&&bounds.outWidth<=4096&&bounds.outHeight<=4096){BitmapFactory.Options options=new BitmapFactory.Options();int sample=1;while(bounds.outWidth/sample>360||bounds.outHeight/sample>540)sample*=2;options.inSampleSize=sample;image=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);}
            }catch(IOException|RuntimeException ignored){/* Title cards remain usable without artwork. */}
            final Bitmap decoded=image;main.post(()->{if(stopped||epoch!=generation)return;loadingPosters.remove(url);if(decoded==null)return;posters.put(url,decoded);
                for(int i=0;i<grid.getChildCount();i++){View child=grid.getChildAt(i);if(child instanceof LinearLayout){View first=((LinearLayout)child).getChildAt(0);if(first instanceof ImageView&&url.equals(first.getTag()))((ImageView)first).setImageBitmap(decoded);}}
            });
        }));
    }
    private TextView text(String value,int size,int color){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(color);return view;}
    private Button button(String label,Runnable action){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(15);b.setTextColor(INK);b.setMinHeight(dp(48));b.setBackgroundResource(R.drawable.dialog_action);b.setOnClickListener(v->action.run());return b;}
    private GradientDrawable surface(int fill,int stroke,int width,int radius){GradientDrawable shape=new GradientDrawable();shape.setColor(fill);shape.setCornerRadius(dp(radius));shape.setStroke(dp(width),stroke);return shape;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
