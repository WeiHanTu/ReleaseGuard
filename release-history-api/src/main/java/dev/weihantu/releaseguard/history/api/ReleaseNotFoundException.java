package dev.weihantu.releaseguard.history.api;

public class ReleaseNotFoundException extends RuntimeException {

  private final String namespace;
  private final String name;

  public ReleaseNotFoundException(String namespace, String name) {
    super("Release not found: " + namespace + "/" + name);
    this.namespace = namespace;
    this.name = name;
  }

  public String getNamespace() {
    return namespace;
  }

  public String getName() {
    return name;
  }
}
