package org.deepsymmetry.beatlink;

import com.google.code.appengine.awt.Color;

public class MyColor {
  private Color color;

  public static final MyColor white     = new MyColor(255, 255, 255);
  public static final MyColor black     = new MyColor(0, 0, 0);
  public static final MyColor pink      = new MyColor(255, 175, 175);
  public static final MyColor red       = new MyColor(255, 0, 0);
  public static final MyColor orange    = new MyColor(255, 200, 0);
  public static final MyColor yellow    = new MyColor(255, 255, 0);
  public static final MyColor green     = new MyColor(0, 255, 0);
  public static final MyColor cyan      = new MyColor(0, 255, 255);
  public static final MyColor blue      = new MyColor(0, 0, 255);

  public MyColor(int r, int g, int b) {
    this.color = new Color(r, g, b);
  }

  public MyColor(int r, int g, int b, int a) {
    this.color = new Color(r, g, b, a);
  }

  public MyColor(Color color) {
    this.color = color;
  }

  public int getRed() {
    return color.getRed();
  }

  public int getGreen() {
    return color.getGreen();
  }

  public int getBlue() {
    return color.getBlue();
  }

  public int getAlpha() {
    return color.getAlpha();
  }

  public Color toAWTColor() {
    return color;
  }

}
