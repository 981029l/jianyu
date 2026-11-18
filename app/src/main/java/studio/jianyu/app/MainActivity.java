package studio.jianyu.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.*;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import java.io.*;
import java.util.Arrays;

public final class MainActivity extends Activity {
    private static final int PAPER = 0xfffafbfd, INK = 0xff19283a, MUTED = 0xff596e83,
            BLUE = 0xff335edc, SOFT = 0xffedf1f6;
    private LinearLayout root, page;
    private EditText prompt;
    private String draft = "", screen = "", current = "";
    private int model, ratio;
    private boolean nsfw;
    private String[] models, wireModels, ratios;
    private static boolean busy;
    private static MainActivity visible;
    private static final int PICK_REFERENCE = 2;
    private Uri reference;
    private ImageService service;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        service = new ImageService(this);
        models = getResources().getStringArray(R.array.models);
        wireModels = getResources().getStringArray(R.array.wire_models);
        ratios = getResources().getStringArray(R.array.ratios);
        if (state != null) {
            draft = state.getString("draft", ""); model = state.getInt("model"); ratio = state.getInt("ratio");
            nsfw = state.getBoolean("nsfw");
            current = state.getString("current", "");
            if (state.containsKey("reference")) reference = Uri.parse(state.getString("reference"));
        }
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        if (state != null && "detail".equals(state.getString("screen"))) detail(current);
        else if (state != null && "library".equals(state.getString("screen"))) library();
        else if (state != null && "loading".equals(state.getString("screen")) && busy) loading();
        else home();
    }
    @Override protected void onStart() {
        super.onStart(); visible = this;
        if (screen.equals("loading") && !busy || screen.equals("home")) home();
        else if (screen.equals("library")) library();
    }
    @Override protected void onStop() {
        if (visible == this) visible = null;
        super.onStop();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        remember(); state.putString("draft",draft); state.putInt("model",model); state.putInt("ratio",ratio);
        state.putBoolean("nsfw", nsfw);
        if (reference != null) state.putString("reference", reference.toString());
        state.putString("screen",screen); state.putString("current",current);
    }
    private void remember() { if (prompt != null) draft = prompt.getText().toString(); }
    private int dp(float v) { return Math.round(getResources().getDisplayMetrics().density*v); }
    private GradientDrawable box(int color, int radius) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(radius)); return shape;
    }
    private TextView text(String value,int size,int color,boolean bold) {
        TextView v=new TextView(this); v.setText(value);v.setTextSize(size);v.setTextColor(color);
        if(bold) v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return v;
    }
    private void add(View view,int height,int top) {
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,height<0?height:dp(height));p.topMargin=dp(top);page.addView(view,p);
    }
    private TextView action(String label,int bg,int fg,Runnable click) {
        TextView v=text(label,15,fg,true);v.setGravity(Gravity.CENTER);v.setBackground(box(bg,14));v.setFocusable(true);v.setOnClickListener(w->click.run());return v;
    }
    private void layout(String name,String title) {
        remember(); prompt=null;screen=name;
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(PAPER);
        root.setFocusableInTouchMode(true);
        if(Build.VERSION.SDK_INT>=35)root.setOnApplyWindowInsetsListener((v,insets)->{
            android.graphics.Insets b=insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            v.setPadding(b.left,b.top,b.right,b.bottom);return insets;
        });
        setContentView(root);ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(dp(22),dp(19),dp(22),dp(22));scroll.addView(page);
        add(text("见屿",14,BLUE,true),-2,0);add(text(title,34,INK,true),-2,9);
    }
    private void nav() {
        LinearLayout tabs=new LinearLayout(this);tabs.setPadding(dp(14),dp(5),dp(14),dp(6));root.addView(tabs);
        for (String label:new String[]{"创作","作品库"}) {
            boolean create=label.equals("创作"),active=create?screen.equals("home"):screen.equals("library");
            TextView tab=action(label,active?SOFT:PAPER,active?BLUE:MUTED,()->{if(create)home();else library();});
            tabs.addView(tab,new LinearLayout.LayoutParams(0,dp(46),1));
        }
    }
    private boolean isGrok() { return "Image 2".equals(wireModels[model]); }
    private void choose(boolean isModel, TextView control) {
        String[] values=isModel?models:ratios;
        new AlertDialog.Builder(this).setTitle(isModel?"生成模型":"图片比例")
            .setSingleChoiceItems(values,isModel?model:ratio,(dialog,index)->{
                if(isModel){model=index;dialog.dismiss();home();}
                else{ratio=index;control.setText(setting(false));dialog.dismiss();}
            }).setNegativeButton("取消",null).show();
    }
    private String setting(boolean isModel) { return isModel?"生成模型　　"+models[model]+"　›":"图片比例　　"+ratios[ratio]+"　›"; }
    private void settingRow(boolean isModel) {
        TextView row=text(setting(isModel),15,INK,false);row.setBackground(box(SOFT,13));row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(15),0,0,0);row.setFocusable(true);row.setOnClickListener(v->choose(isModel,row));add(row,48,7);
    }
    private void nsfwRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(box(SOFT, 13));
        row.setPadding(dp(15), 0, dp(12), 0);
        row.setFocusable(true);
        row.addView(text("允许 NSFW（仅 Grok）", 15, INK, false), new LinearLayout.LayoutParams(0, -2, 1));
        Switch toggle = new Switch(this);
        toggle.setChecked(nsfw);
        toggle.setOnCheckedChangeListener((button, checked) -> nsfw = checked);
        row.addView(toggle);
        row.setOnClickListener(v -> toggle.toggle());
        add(row, 48, 7);
    }
    private void home() {
        layout("home","创作");add(text("画面描述",15,INK,true),-2,19);
        prompt=new EditText(this);prompt.setGravity(Gravity.TOP|Gravity.START);prompt.setTextSize(17);prompt.setTextColor(INK);prompt.setHintTextColor(MUTED);prompt.setSingleLine(false);
        prompt.setHint("描述想生成的画面…");prompt.setText(draft);prompt.setSelection(draft.length());prompt.setPadding(dp(15),dp(12),dp(15),dp(12));
        GradientDrawable field=box(Color.WHITE,15);field.setStroke(dp(1),0xffcfd8e4);prompt.setBackground(field);add(prompt,126,8);
        settingRow(true);settingRow(false);
        if (isGrok()) nsfwRow();
        add(action(reference == null ? "添加参考图（可选）" : "更换参考图", SOFT, INK, this::pickReference), 48, 7);
        if (reference != null) add(action("移除已选参考图", PAPER, MUTED, () -> { reference = null; home(); }), 42, 4);
        boolean pending = service.hasPending();
        String label = pending ? "继续获取上次作品" : reference == null ? "生成图片" : "按描述修改参考图";
        add(action(busy ? "正在处理…" : label, BLUE, Color.WHITE, this::generate), 49, 14);
        if (pending && !busy) add(action("放弃取回上次作品", PAPER, MUTED, () -> new AlertDialog.Builder(this)
                .setMessage("移除本机的取图记录？这不会取消服务端任务。")
                .setNegativeButton("返回", null).setPositiveButton("移除", (d,w) -> { service.forget(); home(); }).show()), 42, 4);
        if(BuildConfig.IMAGE_ENDPOINT.isEmpty())add(text("预览版 · 尚未连接生图服务",12,MUTED,false),-2,8);
        add(text("示例作品",16,INK,true),-2,22);ImageView cover=new ImageView(this);cover.setImageResource(R.drawable.coast);cover.setScaleType(ImageView.ScaleType.CENTER_CROP);cover.setContentDescription("查看示例作品海岸晨光");cover.setBackground(box(SOFT,15));cover.setClipToOutline(true);cover.setOnClickListener(v->detail(""));add(cover,136,8);nav();
    }
    private void pickReference() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
        picker.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/jpeg", "image/png", "image/webp"});
        startActivityForResult(picker, PICK_REFERENCE);
    }
    private void loading() {
        layout("loading", service.hasPending() ? "正在获取作品" : "正在生成");
        add(new ProgressBar(this),64,42);add(text("图片完成后会保存到作品库。",15,MUTED,false),-2,15);nav();
    }
    private void generate() {
        if(busy)return;
        if(BuildConfig.IMAGE_ENDPOINT.isEmpty()){
            new AlertDialog.Builder(this).setTitle("生图服务尚未连接").setMessage("当前可预览示例作品。应用接入服务后即可用自己的提示词生成图片。").setPositiveButton("查看示例",(d,w)->detail("")).setNegativeButton("返回",null).show();return;
        }
        remember();
        String input = draft.trim();
        if (!service.hasPending() && (input.isEmpty() || input.codePointCount(0, input.length()) > 4000)) {
            prompt.setError("请输入 1 至 4000 个字符的画面描述"); return;
        }
        String wireModel = wireModels[model];
        busy = true; loading();
        submit(service, input, wireModel, ratios[ratio], reference, isGrok() && nsfw);
    }
    private static void submit(ImageService service, String input, String model, String ratio, Uri reference, boolean nsfw) {
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                String id = service.run(input, model, ratio, reference, nsfw);
                main.post(() -> finished(id, null));
            } catch (Exception e) {
                String message = e instanceof java.net.SocketTimeoutException || e instanceof java.net.SocketException || e instanceof java.net.UnknownHostException
                        ? "网络连接中断，请先核对原任务记录。" : e.getMessage();
                main.post(() -> finished(null, message == null ? "无法获取作品，请检查网关连接。" : message));
            }
        }, "jianyu-image").start();
    }
    private static void finished(String id, String error) {
        busy = false;
        MainActivity activity = visible;
        if (activity == null) return;
        if (error != null) {
            activity.home();
            new AlertDialog.Builder(activity).setMessage(error).setPositiveButton("知道了", null).show();
        } else if (activity.screen.equals("loading")) activity.detail(id);
        else if (activity.screen.equals("library")) activity.library();
        else if (activity.screen.equals("home")) activity.home();
    }
    private String description(String id){return id.isEmpty()?"清晨的海岸灯塔，远山隐入薄雾，日光在蓝绿色的海面上铺开。":getSharedPreferences("art",MODE_PRIVATE).getString(id,"生成作品");}
    private void library() {
        layout("library","作品库");String[] ids=new File(getFilesDir(),"images").list((dir,n)->n.endsWith(".png")||n.endsWith(".jpg")||n.endsWith(".webp"));
        if(ids==null||ids.length==0)add(text("生成的图片会显示在这里。",15,MUTED,false),-2,20);
        else{Arrays.sort(ids,java.util.Collections.reverseOrder());for(String id:ids){TextView item=action(description(id),SOFT,INK,()->detail(id));item.setPadding(dp(12),0,dp(12),0);item.setMaxLines(2);item.setEllipsize(android.text.TextUtils.TruncateAt.END);add(item,62,10);}}
        nav();
    }
    private void detail(String id) {
        current=id;layout("detail",id.isEmpty()?"海岸晨光":"作品详情");ImageView image=new ImageView(this);image.setAdjustViewBounds(true);image.setBackground(box(SOFT,16));image.setClipToOutline(true);
        if(id.isEmpty())image.setImageResource(R.drawable.coast);
        else {
            File f=new File(new File(getFilesDir(),"images"),id);BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeFile(f.getPath(),o);
            o.inSampleSize=1;while((long)Math.max(o.outWidth,o.outHeight)/o.inSampleSize>1800)o.inSampleSize*=2;
            o.inJustDecodeBounds=false;Bitmap bitmap=BitmapFactory.decodeFile(f.getPath(),o);if(bitmap==null){library();return;}image.setImageBitmap(bitmap);
        }
        add(image,-2,16);if(id.isEmpty())add(text("内置示例",12,MUTED,true),-2,12);add(text(description(id),15,INK,false),-2,10);
        add(action("保存图片",BLUE,Color.WHITE,()->{
            String ext=id.isEmpty()?"jpg":id.substring(id.lastIndexOf('.')+1);String mime="jpg".equals(ext)?"image/jpeg":"image/"+ext;
            Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(mime).addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"见屿-"+(id.isEmpty()?"海岸晨光.jpg":id));startActivityForResult(intent,1);
        }),48,16);nav();
    }
    @Override protected void onActivityResult(int code,int result,Intent intent){
        super.onActivityResult(code,result,intent);
        if(result!=RESULT_OK||intent==null||intent.getData()==null)return;
        if(code==PICK_REFERENCE) { reference=intent.getData();home();return; }
        if(code!=1)return;
        try(InputStream in=current.isEmpty()?getResources().openRawResource(R.drawable.coast):new FileInputStream(new File(new File(getFilesDir(),"images"),current));OutputStream out=getContentResolver().openOutputStream(intent.getData())){
            if(out==null)throw new IOException();ImageService.copy(in,out,ImageService.IMAGE_LIMIT,"作品文件过大。");Toast.makeText(this,"已保存",Toast.LENGTH_SHORT).show();
        }catch(IOException e){Toast.makeText(this,"保存失败，请重新选择位置。",Toast.LENGTH_LONG).show();}
    }
    @Override public void onBackPressed(){if(screen.equals("home"))super.onBackPressed();else if(screen.equals("detail")&&!current.isEmpty())library();else home();}
}
