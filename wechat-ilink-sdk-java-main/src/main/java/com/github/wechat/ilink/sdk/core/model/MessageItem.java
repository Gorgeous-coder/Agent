package com.github.wechat.ilink.sdk.core.model;

public class MessageItem {
  private int type;
  private TextItem text_item;
  private ImageItem image_item;
  private FileItem file_item;
  private VoiceItem voice_item;
  private VideoItem video_item;

  public static MessageItem text(String text) {
    MessageItem item = new MessageItem();
    item.type = 1;
    item.text_item = new TextItem(text);
    return item;
  }

  public int getType() {
    return type;
  }

  public void setType(int v) {
    type = v;
  }

  public TextItem getText_item() {
    return text_item;
  }

  public void setText_item(TextItem t) {
    text_item = t;
  }

  public ImageItem getImage_item() {
    return image_item;
  }

  public void setImage_item(ImageItem v) {
    image_item = v;
  }

  public FileItem getFile_item() {
    return file_item;
  }

  public void setFile_item(FileItem v) {
    file_item = v;
  }

  public VoiceItem getVoice_item() {
    return voice_item;
  }

  public void setVoice_item(VoiceItem v) {
    voice_item = v;
  }

  public VideoItem getVideo_item() {
    return video_item;
  }

  public void setVideo_item(VideoItem v) {
    video_item = v;
  }
}
