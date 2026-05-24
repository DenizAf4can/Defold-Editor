# Defold Editor UI Tools

> H-hah? You wanted custom editor UI without rebuilding Defold?
> Fine. I patched the boring parts. Gently.

This folder contains the editor extension used by **Defold Editor UI Tools**.
Install the package through the root `github_editor_ui` dependency archive, then
configure it with `/editor_ui_tools.edn`.

## Features

- Custom top-level menus.
- Bottom utility tabs.
- Simple editor tabs.
- Project CSS theme loading.
- Theme reload command.
- Image hover previews.
- PNG/JPG/JPEG/WebP preview tabs.

## Config File

Use `/editor_ui_tools.edn` in your project root. The main knobs are:

```clojure
{:theme {:stylesheets ["/editor_theme.css"]}
 :menus [...]
 :tabs [...]
 :image-preview {:asset-browser-hover? true
                 :editor-tab? true
                 :extensions ["png" "jpg" "jpeg" "webp"]}}
```

That is enough. Do not overcomplicate it.

Hmph.
