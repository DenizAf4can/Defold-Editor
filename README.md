# Defold Editor UI Tools

> H-hah? You wanted the Defold editor to have custom menus and themes?
> Fine. I made it dress properly. D-don't get smug about it.

**Defold Editor UI Tools** is a Defold `1.12.4+` library extension for
project-level editor customization. It adds configurable menus, utility tabs,
project CSS themes, and image previews without forking Defold.

## Defold Library

The dependency exposes this library folder through `game.project`:

```ini
[library]
include_dirs = editor_ui_tools
defold_min_version = 1.12.4
```

- `editor_ui_tools/` - the editor extension.

The root config files in this archive are small starters you can adapt in your
own project. They are not magic. Hmph.

## What It Does

- Adds custom top-level menus, such as `Extensions`.
- Adds bottom utility tabs.
- Adds simple protected editor tabs.
- Loads project-local CSS themes.
- Adds a theme reload command.
- Adds an optional status tab.
- Adds hover previews for image resources in the Asset Browser.
- Opens PNG/JPG/JPEG/WebP files in an editor preview tab.

WebP preview works best with **Defold WebP Import** installed. Without it, WebP
preview will fail politely because Java ImageIO has no built-in WebP reader.

## Install

Add the release archive to `game.project`:

```ini
[project]
dependencies#0 = https://github.com/DenizAf4can/Defold-Editor-UI-Tools/archive/refs/tags/v.0.1.zip
```

Then run:

```text
Project -> Fetch Libraries
Project -> Reload Editor Scripts
```

## Configure

Edit `/editor_ui_tools.edn`:

```clojure
{:theme {:stylesheets ["/editor_theme.css"]
         :auto-reload? true}

 :menus [{:id :extensions
          :label "Extensions"
          :items [{:label "Fetch Libraries"
                   :command :project.fetch-libraries}
                  {:label "Reload Editor Scripts"
                   :command :project.reload-editor-scripts}
                  {:label "Reload Project Theme"
                   :command :editor-ui-tools.reload-theme}]}]

 :tabs [{:id :tools
         :label "Extensions"
         :area :bottom
         :type :actions
         :items [{:label "Fetch Libraries"
                  :command :project.fetch-libraries}]}

        {:id :status
         :label "Extensions Status"
         :area :bottom
         :type :status}]

 :image-preview {:asset-browser-hover? true
                 :editor-tab? true
                 :extensions ["png" "jpg" "jpeg" "webp"]}}
```

After config or CSS changes:

```text
Extensions -> Reload Project Theme
```

For editor script changes:

```text
Project -> Reload Editor Scripts
```

## Notes

- This uses small private editor hooks because Defold does not expose all UI
  extension points publicly yet.
- It is not an editor fork.
- Keep configs simple. The editor is cute, not psychic.

There. Your editor has accessories now.

Not that I enjoyed making it look nice or anything.
