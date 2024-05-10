package org.deepsymmetry.beatlink;

public class MyColor {
  private java.awt.Color color;

  public static final MyColor white     = new MyColor(255, 255, 255);
  public static final MyColor black     = new MyColor(0, 0, 0);

  public MyColor(int r, int g, int b) {
    this.color = new java.awt.Color(r, g, b);
  }

  public MyColor(int r, int g, int b, int a) {
    this.color = new java.awt.Color(r, g, b, a);
  }

  public MyColor(java.awt.Color color) {
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

  public java.awt.Color toAWTColor() {
    return color;
  }

}
