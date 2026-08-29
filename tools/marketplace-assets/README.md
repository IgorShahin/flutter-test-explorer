# Marketplace screenshots

The screenshots are 1280×800 Marketplace slides built around the real `Tests` component rendered by the sandbox smoke plugin. No application project or generated IDE UI is used.

Regenerate the sandbox capture first if the explorer UI changes, then run:

```bash
mkdir -p build/marketplace-assets-classes
javac --release 21 -d build/marketplace-assets-classes tools/marketplace-assets/GenerateMarketplaceScreenshots.java
java -Djava.awt.headless=true -cp build/marketplace-assets-classes marketplace.GenerateMarketplaceScreenshots \
  build/test_explorer_smoke/tool-window.png docs/marketplace
```

Upload the PNG files in their numbered order to the JetBrains Marketplace **Media** section. The same files are referenced from `plugin.xml` using their public `raw.githubusercontent.com` URLs.
