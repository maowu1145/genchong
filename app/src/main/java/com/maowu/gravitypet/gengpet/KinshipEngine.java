package com.maowu.gravitypet.gengpet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Original common-Mandarin rule table. Explicit ambiguity is retained, never guessed away. Tokens:
 * f/m parents, h/w spouses, ob/lb older/younger brother, os/ls sister, xb/xs sibling with unknown
 * age, s/d children. No external licensed rule payload. Unlisted chains return a readable
 * unsupported result, not an invented title.
 */
public final class KinshipEngine {
  public enum Sex {
    UNKNOWN,
    MALE,
    FEMALE
  }

  public static final String[] TOKENS = {
    "f", "m", "h", "w", "ob", "lb", "os", "ls", "xb", "xs", "s", "d"
  };
  public static final String[] LABELS = {
    "爸爸", "妈妈", "丈夫", "妻子", "哥哥", "弟弟", "姐姐", "妹妹", "兄弟（长幼未知）", "姐妹（长幼未知）", "儿子", "女儿"
  };
  private static final Map<String, String> RULES = new LinkedHashMap<>();

  static {
    add("", "自己");
    add("f", "爸爸 / 父亲");
    add("m", "妈妈 / 母亲");
    add("h", "丈夫 / 老公");
    add("w", "妻子 / 老婆");
    add("ob", "哥哥");
    add("lb", "弟弟");
    add("os", "姐姐");
    add("ls", "妹妹");
    add("s", "儿子");
    add("d", "女儿");
    add("f,f", "爷爷 / 祖父");
    add("f,m", "奶奶 / 祖母");
    add("m,f", "外公 / 姥爷");
    add("m,m", "外婆 / 姥姥");
    add("f,ob", "伯父 / 伯伯");
    add("f,lb", "叔父 / 叔叔");
    add("f,os", "姑妈 / 姑姑");
    add("f,ls", "姑妈 / 姑姑");
    add("m,ob", "舅舅 / 舅父");
    add("m,lb", "舅舅 / 舅父");
    add("m,os", "姨妈 / 姨母");
    add("m,ls", "姨妈 / 姨母");
    add("f,ob,w", "伯母");
    add("f,lb,w", "婶婶 / 婶母");
    add("f,os,h", "姑父");
    add("f,ls,h", "姑父");
    add("m,ob,w", "舅妈 / 舅母");
    add("m,lb,w", "舅妈 / 舅母");
    add("m,os,h", "姨父");
    add("m,ls,h", "姨父");
    add("ob,w", "嫂子");
    add("lb,w", "弟媳");
    add("os,h", "姐夫");
    add("ls,h", "妹夫");
    for (String b : new String[] {"ob", "lb"}) {
      add(b + ",s", "侄子");
      add(b + ",d", "侄女");
      add(b + ",s,w", "侄媳妇");
      add(b + ",d,h", "侄女婿");
    }
    for (String s : new String[] {"os", "ls"}) {
      add(s + ",s", "外甥");
      add(s + ",d", "外甥女");
      add(s + ",s,w", "外甥媳妇");
      add(s + ",d,h", "外甥女婿");
    }
    add("s,w", "儿媳 / 媳妇");
    add("d,h", "女婿");
    add("s,s", "孙子");
    add("s,d", "孙女");
    add("d,s", "外孙");
    add("d,d", "外孙女");
    add("s,s,w", "孙媳妇");
    add("s,d,h", "孙女婿");
    add("d,s,w", "外孙媳妇");
    add("d,d,h", "外孙女婿");
    add("h,f", "公公");
    add("h,m", "婆婆");
    add("w,f", "岳父 / 丈人");
    add("w,m", "岳母 / 丈母娘");
    add("h,ob", "大伯子");
    add("h,lb", "小叔子");
    add("h,os", "大姑子");
    add("h,ls", "小姑子");
    add("w,ob", "大舅子");
    add("w,lb", "小舅子");
    add("w,os", "大姨子");
    add("w,ls", "小姨子");
    add("h,ob,w", "嫂子 / 妯娌");
    add("h,lb,w", "弟媳 / 妯娌");
    add("w,os,h", "姐夫 / 连襟");
    add("w,ls,h", "妹夫 / 连襟");
    add("s,w,f", "亲家公");
    add("s,w,m", "亲家母");
    add("d,h,f", "亲家公");
    add("d,h,m", "亲家母");
    add("f,f,f", "曾祖父 / 太爷爷");
    add("f,f,m", "曾祖母 / 太奶奶");
    add("f,m,f", "曾外祖父（奶奶的父亲）");
    add("f,m,m", "曾外祖母（奶奶的母亲）");
    add("m,f,f", "外曾祖父（外公的父亲）");
    add("m,f,m", "外曾祖母（外公的母亲）");
    add("m,m,f", "外曾外祖父");
    add("m,m,m", "外曾外祖母");
    add("f,f,ob", "伯祖父 / 伯公");
    add("f,f,lb", "叔祖父 / 叔公");
    add("f,f,os", "姑祖母 / 姑奶奶");
    add("f,f,ls", "姑祖母 / 姑奶奶");
    add("s,s,s", "曾孙");
    add("s,s,d", "曾孙女");
    for (String parent : new String[] {"f", "m"})
      for (String sibling : new String[] {"ob", "lb", "os", "ls"}) {
        String prefix =
            parent.equals("f") && (sibling.equals("ob") || sibling.equals("lb")) ? "堂" : "表";
        add(parent + "," + sibling + ",s", prefix + "哥 / " + prefix + "弟（需比较与你的年龄）");
        add(parent + "," + sibling + ",d", prefix + "姐 / " + prefix + "妹（需比较与你的年龄）");
      }
    // Moving from a sibling back to a parent is defined for the usual full-sibling model.
    for (String sibling : new String[] {"ob", "lb", "os", "ls"}) {
      add(sibling + ",f", "爸爸 / 父亲");
      add(sibling + ",m", "妈妈 / 母亲");
    }
    add("f,w", "妈妈 / 继母（需确认是否生母）");
    add("m,h", "爸爸 / 继父（需确认是否生父）");
  }

  private static void add(String path, String title) {
    RULES.put(path, title);
  }

  public static Map<String, String> rules() {
    return Collections.unmodifiableMap(RULES);
  }

  public static final class Result {
    public final String text;
    public final boolean supported;

    Result(String text, boolean supported) {
      this.text = text;
      this.supported = supported;
    }
  }

  public static String label(String token) {
    for (int i = 0; i < TOKENS.length; i++) if (TOKENS[i].equals(token)) return LABELS[i];
    return "未知关系";
  }

  public static String describe(List<String> path) {
    StringBuilder result = new StringBuilder("我");
    for (String token : path) result.append(" → ").append(label(token));
    return result.toString();
  }

  public static Result calculate(Sex sex, List<String> chain) {
    if (chain == null || chain.size() > 8) return new Result("关系链最多 8 步；请拆开确认", false);
    for (String token : chain)
      if (!Arrays.asList(TOKENS).contains(token)) return new Result("存在未知关系，请重新选择", false);
    // Sex is information about the chain's current person, not age or sibling order.
    Sex current = sex;
    for (String token : chain) {
      if ((token.equals("h") && current == Sex.MALE)
          || (token.equals("w") && current == Sex.FEMALE))
        return new Result("当前称谓表采用传统夫妻称谓；这条配偶关系不在规则范围内", false);
      current =
          Arrays.asList("f", "h", "ob", "lb", "xb", "s").contains(token) ? Sex.MALE : Sex.FEMALE;
    }
    ArrayList<List<String>> expanded = new ArrayList<>();
    expand(chain, 0, new ArrayList<>(), expanded);
    LinkedHashSet<String> answers = new LinkedHashSet<>();
    boolean all = true;
    for (List<String> path : expanded) {
      ArrayList<String> p = new ArrayList<>(path);
      // Adjacent spouse inverses are the same person under the stated current-marriage model.
      for (int i = 0; i + 1 < p.size(); ) {
        if ((p.get(i).equals("h") && p.get(i + 1).equals("w"))
            || (p.get(i).equals("w") && p.get(i + 1).equals("h"))) {
          p.remove(i);
          p.remove(i);
          i = Math.max(0, i - 1);
        } else i++;
      }
      String answer = RULES.get(String.join(",", p));
      if (answer == null && p.size() == 2 && (p.get(0).equals("f") || p.get(0).equals("m"))) {
        if (p.get(1).equals("s"))
          answer = sex == Sex.FEMALE ? "哥哥 / 弟弟（长幼未知）" : "自己 / 哥哥 / 弟弟（需确认是否本人）";
        if (p.get(1).equals("d"))
          answer = sex == Sex.MALE ? "姐姐 / 妹妹（长幼未知）" : "自己 / 姐姐 / 妹妹（需确认是否本人）";
      }
      if (answer == null) {
        all = false;
        answers.add("这条复杂关系暂无可靠称谓，请分段确认");
      } else answers.add(answer);
    }
    return new Result(String.join("；", answers), all);
  }

  private static void expand(
      List<String> source, int at, ArrayList<String> path, List<List<String>> out) {
    if (at == source.size()) {
      out.add(new ArrayList<>(path));
      return;
    }
    String token = source.get(at);
    String[] choices =
        token.equals("xb")
            ? new String[] {"ob", "lb"}
            : token.equals("xs") ? new String[] {"os", "ls"} : new String[] {token};
    for (String choice : choices) {
      path.add(choice);
      expand(source, at + 1, path, out);
      path.remove(path.size() - 1);
    }
  }

  public static final class Chain {
    private final ArrayList<String> values = new ArrayList<>();

    public boolean add(String token) {
      if (values.size() >= 8 || !Arrays.asList(TOKENS).contains(token)) return false;
      values.add(token);
      return true;
    }

    public void undo() {
      if (!values.isEmpty()) values.remove(values.size() - 1);
    }

    public void clear() {
      values.clear();
    }

    public List<String> values() {
      return Collections.unmodifiableList(values);
    }
  }
}
