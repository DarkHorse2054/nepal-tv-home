package app.nepal.framextv;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Native browse home. Fixed-size shelves, explicit D-pad routes, and visible-only artwork. */
final class TheaterHomeView extends LinearLayout {
    interface Listener {
        void select(CatalogClient.Item item);
        void resume(WatchHistory.Entry entry);
        void search();
        void navigate();
    }
    private static final int BG=0xFF090A0D, CARD=0xFF17191E, INK=0xFFF5F5F6, MUTED=0xFFB4B6BE, RED=0xFFE44955;
    private static final int SHELF_LIMIT=12;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final CatalogClient catalog=new CatalogClient();
    private final CatalogArtwork artwork;
    private final WatchHistory history;
    private final Listener listener;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final List<Shelf> shelves=new ArrayList<>();
    private final List<Button> tabs=new ArrayList<>();
    private final ScrollView scroll;
    private final LinearLayout content, shelfHost;
    private final TextView status,heroTitle,heroDetail,heroOverview;
    private final FrameLayout hero;
    private final ImageView heroImage;
    private final Button heroAction,retry;
    private Map<CatalogClient.BrowseSection,List<CatalogClient.Item>> catalogRows;
    private Future<?> pending,detailPending;
    private final Map<String,CatalogClient.Item> continuedItems=new LinkedHashMap<>();
    private final Set<String> detailAttempts=new HashSet<>();
    private int generation;
    private boolean active,closed,loading,failed;
    private String tab="Home",focusedKey="",historySignature="";
    private CatalogClient.Item featured;

    TheaterHomeView(Context context,WatchHistory history,Listener listener) {
        super(context);this.history=history;this.listener=listener;artwork=new CatalogArtwork(context);
        setOrientation(VERTICAL);setBackgroundColor(BG);setFocusable(false);
        LinearLayout nav=new LinearLayout(context);nav.setGravity(Gravity.CENTER_VERTICAL);nav.setPadding(dp(36),dp(8),dp(36),dp(6));
        ImageView logo=new ImageView(context);logo.setImageResource(R.drawable.ic_framextv);logo.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        nav.addView(logo,new LayoutParams(dp(38),dp(38)));
        TextView brand=text("THEATER",18,INK);brand.setLetterSpacing(.12f);brand.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        LayoutParams brandLayout=new LayoutParams(0,dp(48),1);brandLayout.leftMargin=dp(10);brand.setGravity(Gravity.CENTER_VERTICAL);nav.addView(brand,brandLayout);
        for(String name:new String[]{"Home","Movies","TV shows","Search"}) {
            Button button=action(name,()->{if("Search".equals(name))listener.search();else{listener.navigate();showTab(name);}});
            button.setTextSize(14);button.setMinWidth(0);button.setMinimumWidth(0);button.setTag(name);
            LayoutParams lp=new LayoutParams(dp("TV shows".equals(name)?100:86),dp(44));lp.leftMargin=dp(8);nav.addView(button,lp);tabs.add(button);
            button.setOnKeyListener((v,key,event)->{if(event.getAction()==KeyEvent.ACTION_DOWN&&key==KeyEvent.KEYCODE_DPAD_DOWN){focusFirst();return true;}return false;});
        }
        addView(nav,new LayoutParams(-1,dp(64)));
        status=text("",13,MUTED);status.setPadding(dp(42),dp(4),dp(42),dp(4));status.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);status.setVisibility(GONE);
        addView(status,new LayoutParams(-1,-2));
        scroll=new ScrollView(context);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);scroll.setClipToPadding(false);scroll.setSmoothScrollingEnabled(false);scroll.setFocusable(false);
        content=new LinearLayout(context);content.setOrientation(VERTICAL);content.setPadding(0,0,0,dp(30));content.setFocusable(false);scroll.addView(content,new ScrollView.LayoutParams(-1,-2));
        addView(scroll,new LayoutParams(-1,0,1));scroll.setOnScrollChangeListener((v,x,y,ox,oy)->artwork.refreshVisible());
        hero=new FrameLayout(context);hero.setBackgroundColor(BG);
        heroImage=new ImageView(context);heroImage.setScaleType(ImageView.ScaleType.CENTER_CROP);heroImage.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams art=new FrameLayout.LayoutParams(dp(590),-1,Gravity.RIGHT);hero.addView(heroImage,art);
        View shade=new View(context);shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,new int[]{BG,0xF0090A0D,0x22090A0D}));hero.addView(shade,new FrameLayout.LayoutParams(-1,-1));
        View bottom=new View(context);bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0x00090A0D,BG}));hero.addView(bottom,new FrameLayout.LayoutParams(-1,dp(60),Gravity.BOTTOM));
        LinearLayout identity=new LinearLayout(context);identity.setOrientation(VERTICAL);identity.setPadding(dp(42),dp(16),dp(20),0);
        heroDetail=text("YOUR NEXT WATCH",11,RED);heroDetail.setLetterSpacing(.15f);heroDetail.setTypeface(Typeface.DEFAULT_BOLD);identity.addView(heroDetail);
        heroTitle=text("Find your next favorite.",30,INK);heroTitle.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));heroTitle.setMaxLines(1);heroTitle.setEllipsize(TextUtils.TruncateAt.END);heroTitle.setPadding(0,dp(6),0,dp(5));identity.addView(heroTitle);
        heroOverview=text("Movies, shows, and where you left off.",14,MUTED);heroOverview.setMaxLines(2);heroOverview.setEllipsize(TextUtils.TruncateAt.END);identity.addView(heroOverview,new LayoutParams(-1,dp(40)));
        heroAction=action("Browse titles",()->{if(featured!=null)listener.select(featured);else focusFirstShelf();});
        LayoutParams watchLayout=new LayoutParams(dp(168),dp(44));watchLayout.topMargin=dp(8);identity.addView(heroAction,watchLayout);
        heroAction.setOnKeyListener((v,key,event)->{if(event.getAction()!=KeyEvent.ACTION_DOWN)return false;if(key==KeyEvent.KEYCODE_DPAD_DOWN){focusAfterHero();return true;}if(key==KeyEvent.KEYCODE_DPAD_UP){selectedTab().requestFocus();return true;}return false;});
        hero.addView(identity,new FrameLayout.LayoutParams(dp(490),-1));content.addView(hero,new LayoutParams(-1,dp(186)));
        retry=action("Retry browsing",this::requestCatalog);retry.setVisibility(GONE);LayoutParams retryLayout=new LayoutParams(dp(188),dp(44));retryLayout.leftMargin=dp(42);content.addView(retry,retryLayout);
        retry.setOnKeyListener((v,key,event)->{if(event.getAction()!=KeyEvent.ACTION_DOWN)return false;if(key==KeyEvent.KEYCODE_DPAD_UP){heroAction.requestFocus();return true;}if(key==KeyEvent.KEYCODE_DPAD_DOWN){focusFirstShelf();return true;}return false;});
        shelfHost=new LinearLayout(context);shelfHost.setOrientation(VERTICAL);content.addView(shelfHost,new LayoutParams(-1,-2));
        updateTabs();renderShelves();
    }

    void activate() {
        if(closed)return;active=true;artwork.resume();refreshHistory();
        if(catalogRows==null&&!loading&&!failed)requestCatalog();else if(catalogRows!=null){showStatus("");loadContinueArt();}
        handler.post(artwork::refreshVisible);
    }
    void pause() {
        active=false;generation++;loading=false;if(pending!=null)pending.cancel(true);if(detailPending!=null)detailPending.cancel(true);detailAttempts.clear();catalog.cancelAll();artwork.pause();
    }
    void close() {if(closed)return;pause();closed=true;worker.shutdownNow();catalog.close();artwork.close();handler.removeCallbacksAndMessages(null);}
    void showStatus(String message) {status.setText(message);status.setVisibility(message==null||message.isEmpty()?GONE:VISIBLE);}
    boolean backToHome() {if(!"Home".equals(tab)){listener.navigate();showTab("Home");return true;}return false;}
    void focusNavigation() {selectedTab().requestFocus();}
    void refreshHistory() {
        String signature=historySignature();if(signature.equals(historySignature))return;
        View old=findFocus();boolean restore=old!=null&&old.getTag() instanceof String&&((String)old.getTag()).contains(":");
        final String savedFocus=focusedKey;
        int y=scroll.getScrollY();Shelf prior=null;
        for(Shelf shelf:shelves)if(shelf.continuing){prior=shelf;break;}
        int x=prior==null?0:prior.scroller.getScrollX();
        if(prior!=null){shelfHost.removeView(prior.container);shelves.remove(prior);}
        addContinueShelf();historySignature=signature;rewireFocus();
        afterLayout(()->{
            if(!active)return;
            for(Shelf shelf:shelves)if(shelf.continuing)shelf.scroller.scrollTo(x,0);
            scroll.scrollTo(0,y);
            if(restore&&savedFocus.equals(focusedKey)){
                if(!restoreCardFocus())focusFirstShelf();
                View focused=findFocus();if(focused!=null)focused.requestRectangleOnScreen(new Rect(0,0,focused.getWidth(),focused.getHeight()),true);
            }
            artwork.refreshVisible();
        });
    }
    private String historySignature() {StringBuilder b=new StringBuilder();for(WatchHistory.Entry e:history.entries())b.append(e.mediaType).append(':').append(e.id).append(':').append(e.season).append(':').append(e.episode).append(':').append((long)e.positionSeconds).append(';');return b.toString();}
    private void showTab(String name) {
        if(name.equals(tab)){selectedTab().requestFocus();return;}tab=name;focusedKey="";renderShelves();updateTabs();scroll.scrollTo(0,0);selectedTab().requestFocus();loadContinueArt();
    }
    private void updateTabs(){for(Button b:tabs)b.setSelected(tab.equals(b.getTag()));}
    private Button selectedTab(){for(Button b:tabs)if(tab.equals(b.getTag()))return b;return tabs.get(0);}
    private void requestCatalog() {
        if(!active||closed||loading)return;failed=false;loading=true;retry.setVisibility(GONE);showStatus("Loading your library…");final int epoch=++generation;
        pending=worker.submit(()->{
            try{Map<CatalogClient.BrowseSection,List<CatalogClient.Item>> result=catalog.home();handler.post(()->{
                if(!active||closed||epoch!=generation)return;loading=false;catalogRows=result;showStatus("");
                int y=scroll.getScrollY();boolean cardFocused=findFocus()!=null&&findFocus().getTag() instanceof String&&((String)findFocus().getTag()).contains(":");
                renderShelves();if(cardFocused)restoreCardFocus();scroll.scrollTo(0,y);loadContinueArt();
            });}catch(IOException|RuntimeException error){handler.post(()->{if(!active||closed||epoch!=generation)return;loading=false;failed=true;showStatus("Browsing is unavailable. Search and saved progress are still here.");retry.setVisibility(VISIBLE);});}
        });
    }
    private CatalogClient.BrowseSection[] sections() {
        if("Movies".equals(tab))return new CatalogClient.BrowseSection[]{CatalogClient.BrowseSection.NEW_RELEASES,CatalogClient.BrowseSection.POPULAR_MOVIES,CatalogClient.BrowseSection.ACTION_MOVIES,CatalogClient.BrowseSection.SCIFI_MOVIES,CatalogClient.BrowseSection.THRILLER_MOVIES,CatalogClient.BrowseSection.COMEDY_MOVIES};
        if("TV shows".equals(tab))return new CatalogClient.BrowseSection[]{CatalogClient.BrowseSection.POPULAR_TV};
        return new CatalogClient.BrowseSection[]{CatalogClient.BrowseSection.TRENDING_MOVIES,CatalogClient.BrowseSection.NEW_RELEASES,CatalogClient.BrowseSection.POPULAR_MOVIES,CatalogClient.BrowseSection.POPULAR_TV};
    }
    private void renderShelves() {
        artwork.clearBindings();shelves.clear();shelfHost.removeAllViews();addContinueShelf();historySignature=historySignature();featured=null;
        if(catalogRows!=null)for(CatalogClient.BrowseSection section:sections()) {
            List<CatalogClient.Item> items=catalogRows.get(section);if(items==null||items.isEmpty())continue;
            Shelf shelf=new Shelf(section.label,false);int count=0;
            for(CatalogClient.Item item:items){if("Movies".equals(tab)&&!"movie".equals(item.type))continue;if(featured==null)featured=item;addCard(shelf,item,null);if(++count>=SHELF_LIMIT)break;}
            if(!shelf.cards.isEmpty()){shelves.add(shelf);shelfHost.addView(shelf.container);}
        }
        if(catalogRows==null){TextView loading=text("Browse by mood. Pick up where you left off.",15,MUTED);loading.setPadding(dp(42),dp(24),dp(42),dp(24));shelfHost.addView(loading);}
        else if(shelves.isEmpty()){TextView empty=text("No titles in this category right now.",16,MUTED);empty.setPadding(dp(42),dp(24),dp(42),dp(24));shelfHost.addView(empty);}
        updateHero();rewireFocus();handler.post(artwork::refreshVisible);
    }
    private void addContinueShelf() {
        List<WatchHistory.Entry> entries=history.entries();Shelf shelf=new Shelf("Continue watching",true);
        for(WatchHistory.Entry entry:entries){if("Movies".equals(tab)&&!"movie".equals(entry.mediaType)||"TV shows".equals(tab)&&!"tv".equals(entry.mediaType))continue;
            CatalogClient.Item match=findItem(entry.mediaType,entry.id);addCard(shelf,match,entry);if(shelf.cards.size()>=SHELF_LIMIT)break;}
        if(!shelf.cards.isEmpty()){shelves.add(0,shelf);shelfHost.addView(shelf.container,0);}
    }
    private CatalogClient.Item findItem(String type,int id){if(catalogRows!=null)for(List<CatalogClient.Item> list:catalogRows.values())for(CatalogClient.Item item:list)if(item.id==id&&item.type.equals(type))return item;return continuedItems.get(type+":"+id);}
    private void loadContinueArt() {
        if(!active||closed||catalogRows==null||detailPending!=null&&!detailPending.isDone())return;
        List<WatchHistory.Entry> missing=new ArrayList<>();
        for(WatchHistory.Entry e:history.entries()){
            String key=e.mediaType+":"+e.id;
            if(findItem(e.mediaType,e.id)==null&&detailAttempts.add(key))missing.add(e);
            if(missing.size()>=6)break;
        }
        if(missing.isEmpty())return;final int epoch=generation;
        detailPending=worker.submit(()->{for(WatchHistory.Entry e:missing){
            if(Thread.currentThread().isInterrupted())return;
            try{CatalogClient.Item item=catalog.details(e.mediaType,e.id);handler.post(()->{
                if(!active||closed||epoch!=generation)return;
                continuedItems.put(item.type+":"+item.id,item);
                while(continuedItems.size()>24)continuedItems.remove(continuedItems.keySet().iterator().next());
                historySignature="";refreshHistory();
            });}catch(IOException|RuntimeException unavailable){/* Saved progress stays usable without artwork. */}
        }});
    }
    private void updateHero() {
        if(featured==null){heroDetail.setText("YOUR NEXT WATCH");heroTitle.setText("Find your next favorite.");heroOverview.setText("Movies, shows, and where you left off.");heroAction.setText("Browse titles");return;}
        heroDetail.setText(("tv".equals(featured.type)?"FEATURED SERIES":"FEATURED MOVIE")+(featured.year.isEmpty()?"":"  ·  "+featured.year));heroTitle.setText(featured.title);
        heroOverview.setText(featured.overview.isEmpty()?"Choose something new tonight.":featured.overview);heroAction.setText("tv".equals(featured.type)?"Choose episode":"Watch movie");heroAction.setContentDescription(heroAction.getText()+", "+featured.title);
        artwork.bind(heroImage,featured.backdropUrl,dp(590),dp(332));
    }
    private void addCard(Shelf shelf,CatalogClient.Item item,WatchHistory.Entry saved) {
        String title=saved!=null?saved.title:item.title;
        String type=saved!=null?saved.mediaType:item.type;
        String key=saved!=null?"continue:"+type+":"+saved.id+":"+saved.season+":"+saved.episode:shelf.title+":"+type+":"+item.id;
        LinearLayout card=new LinearLayout(getContext());card.setId(View.generateViewId());card.setOrientation(VERTICAL);card.setPadding(dp(5),dp(5),dp(5),dp(7));card.setFocusable(true);card.setClickable(true);card.setBackground(cardBackground());card.setTag(key);
        int width=saved!=null?208:126,height=saved!=null?92:164;
        FrameLayout picture=new FrameLayout(getContext());picture.setBackground(shape(CARD,0,0,7));picture.setClipToOutline(true);
        ImageView poster=new ImageView(getContext());poster.setScaleType(ImageView.ScaleType.CENTER_CROP);poster.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);picture.addView(poster,new FrameLayout.LayoutParams(-1,-1));
        if(saved!=null&&item==null){TextView fallback=text("THEATER",12,MUTED);fallback.setLetterSpacing(.12f);fallback.setGravity(Gravity.CENTER);picture.addView(fallback,new FrameLayout.LayoutParams(-1,-1));}
        card.addView(picture,new LayoutParams(-1,dp(height)));
        String artworkUrl=item==null?"":saved!=null&&!item.backdropUrl.isEmpty()?item.backdropUrl:item.posterUrl;
        artwork.bind(poster,artworkUrl,dp(width),dp(height));
        if(saved!=null){ProgressBar bar=new ProgressBar(getContext(),null,android.R.attr.progressBarStyleHorizontal);bar.setMax(1000);bar.setProgress((int)Math.max(0,Math.min(1000,saved.positionSeconds/Math.max(1,saved.durationSeconds)*1000)));bar.setProgressTintList(android.content.res.ColorStateList.valueOf(RED));bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF393B43));card.addView(bar,new LayoutParams(-1,dp(3)));}
        TextView name=text(title,14,INK);name.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));name.setMaxLines(saved!=null?1:2);name.setMinLines(saved!=null?1:2);name.setEllipsize(TextUtils.TruncateAt.END);name.setPadding(dp(3),dp(7),dp(3),0);card.addView(name,new LayoutParams(-1,-2));
        String detail=saved!=null?("tv".equals(type)?"S"+saved.season+" · E"+saved.episode+"  ·  ":"")+time(saved.positionSeconds)+" watched":("tv".equals(type)?"Series":"Movie")+(item.year.isEmpty()?"":" · "+item.year);
        TextView meta=text(detail,11,MUTED);meta.setMaxLines(1);meta.setEllipsize(TextUtils.TruncateAt.END);meta.setPadding(dp(3),dp(3),dp(3),0);card.addView(meta,new LayoutParams(-1,-2));
        for(int n=0;n<card.getChildCount();n++)card.getChildAt(n).setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.setContentDescription(title+", "+detail+(saved!=null?", resume options":""));
        card.setOnClickListener(v->{if(!active)return;if(saved!=null)listener.resume(saved);else listener.select(item);});
        card.setOnFocusChangeListener((v,focused)->{if(focused){focusedKey=key;shelf.last=shelf.cards.indexOf(card);card.requestRectangleOnScreen(new Rect(0,0,card.getWidth(),card.getHeight()),true);handler.post(artwork::refreshVisible);}});
        card.setOnKeyListener((v,keyCode,event)->{if(event.getAction()!=KeyEvent.ACTION_DOWN)return false;int index=shelf.cards.indexOf(card);
            if(keyCode==KeyEvent.KEYCODE_DPAD_LEFT||keyCode==KeyEvent.KEYCODE_DPAD_RIGHT){int next=index+(keyCode==KeyEvent.KEYCODE_DPAD_LEFT?-1:1);if(next>=0&&next<shelf.cards.size())shelf.cards.get(next).requestFocus();return true;}
            if(keyCode==KeyEvent.KEYCODE_DPAD_UP||keyCode==KeyEvent.KEYCODE_DPAD_DOWN){int row=shelves.indexOf(shelf)+(keyCode==KeyEvent.KEYCODE_DPAD_UP?-1:1);if(row<0){if(retry.getVisibility()==VISIBLE)retry.requestFocus();else heroAction.requestFocus();}else if(row<shelves.size()){Shelf next=shelves.get(row);next.cards.get(Math.min(next.last,next.cards.size()-1)).requestFocus();}return true;}return false;});
        LayoutParams lp=new LayoutParams(dp(width),-2);lp.rightMargin=dp(12);shelf.row.addView(card,lp);shelf.cards.add(card);
    }
    private void afterLayout(Runnable action){
        getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener(){
            @Override public void onGlobalLayout(){if(getViewTreeObserver().isAlive())getViewTreeObserver().removeOnGlobalLayoutListener(this);if(!closed)action.run();}
        });
    }
    private void rewireFocus(){for(Shelf shelf:shelves){for(int i=0;i<shelf.cards.size();i++){View v=shelf.cards.get(i);v.setNextFocusLeftId(shelf.cards.get(Math.max(0,i-1)).getId());v.setNextFocusRightId(shelf.cards.get(Math.min(shelf.cards.size()-1,i+1)).getId());}}}
    private boolean restoreCardFocus(){for(Shelf s:shelves)for(View v:s.cards)if(focusedKey.equals(v.getTag()))return v.requestFocus();return false;}
    private void focusFirst(){heroAction.requestFocus();}
    private void focusAfterHero(){if(retry.getVisibility()==VISIBLE)retry.requestFocus();else focusFirstShelf();}
    private void focusFirstShelf(){if(!shelves.isEmpty())shelves.get(0).cards.get(Math.min(shelves.get(0).last,shelves.get(0).cards.size()-1)).requestFocus();else if(retry.getVisibility()==VISIBLE)retry.requestFocus();else selectedTab().requestFocus();}
    private final class Shelf {
        final String title;final boolean continuing;final LinearLayout container,row;final HorizontalScrollView scroller;final List<View> cards=new ArrayList<>();int last;
        Shelf(String title,boolean continuing){this.title=title;this.continuing=continuing;container=new LinearLayout(getContext());container.setOrientation(VERTICAL);container.setPadding(0,dp(12),0,dp(8));
            TextView heading=text(title,18,INK);heading.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));heading.setPadding(dp(42),0,dp(42),dp(8));container.addView(heading);
            scroller=new HorizontalScrollView(getContext());scroller.setHorizontalScrollBarEnabled(false);scroller.setClipToPadding(false);scroller.setPadding(dp(36),0,dp(30),0);scroller.setFocusable(false);scroller.setSmoothScrollingEnabled(false);
            row=new LinearLayout(getContext());row.setPadding(dp(1),dp(2),0,dp(2));scroller.addView(row,new HorizontalScrollView.LayoutParams(-2,-2));container.addView(scroller,new LayoutParams(-1,-2));scroller.setOnScrollChangeListener((v,x,y,ox,oy)->artwork.refreshVisible());
        }
    }
    private Button action(String title,Runnable action){Button b=new Button(getContext());b.setText(title);b.setAllCaps(false);b.setTextColor(INK);b.setTextSize(14);b.setMinHeight(0);b.setMinimumHeight(0);b.setPadding(dp(12),0,dp(12),0);b.setBackground(cardBackground());b.setOnClickListener(v->action.run());return b;}
    private StateListDrawable cardBackground(){StateListDrawable states=new StateListDrawable();states.addState(new int[]{android.R.attr.state_focused},shape(0xFF272A32,0xFFFFFFFF,2,9));states.addState(new int[]{android.R.attr.state_pressed},shape(0xFF272A32,0xFFFFFFFF,2,9));states.addState(new int[]{android.R.attr.state_selected},shape(0xFF382027,RED,1,9));states.addState(new int[]{},shape(CARD,0xFF24262D,1,9));return states;}
    private GradientDrawable shape(int color,int line,int width,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));if(width>0)d.setStroke(dp(width),line);return d;}
    private TextView text(String value,int size,int color){TextView v=new TextView(getContext());v.setText(value);v.setTextSize(size);v.setTextColor(color);return v;}
    private static String time(double value){long s=(long)Math.max(0,value);return s>=3600?String.format(Locale.US,"%d:%02d:%02d",s/3600,s/60%60,s%60):String.format(Locale.US,"%d:%02d",s/60,s%60);}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
