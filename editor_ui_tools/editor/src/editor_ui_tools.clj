(ns editor.editor-ui-tools
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [cljfx.fx.image-view :as fx.image-view]
            [cljfx.fx.tooltip :as fx.tooltip]
            [dynamo.graph :as g]
            [editor.asset-browser]
            [editor.app-view]
            [editor.editor-tab :as editor-tab]
            [editor.fxui :as fxui]
            [editor.handler :as handler]
            [editor.resource :as resource]
            [editor.ui :as ui]
            [editor.view :as view]
            [editor.workspace :as workspace]
            [service.log :as log])
  (:import [java.awt RenderingHints]
           [java.awt.image BufferedImage]
           [java.io ByteArrayOutputStream File]
           [java.util Collection]
           [javax.imageio ImageIO]
           [javafx.event Event]
           [javafx.geometry Pos]
           [javafx.scene Node Parent Scene]
           [javafx.scene.control Button Label ScrollPane Tab TabPane Tooltip]
           [javafx.scene.image Image ImageView WritableImage]
           [javafx.scene.layout AnchorPane HBox Priority Region VBox]
           [javafx.scene.text Font FontWeight]))

(set! *warn-on-reflection* true)

(def ^:private config-file-name "editor_ui_tools.edn")
(def ^:private image-preview-view-id :editor-ui-tools/image-preview)
(def ^:private default-image-exts ["png" "jpg" "jpeg" "webp"])
(def ^:private protected-tab-key ::protected-editor-tab)

(def ^:private default-config
  {:theme {:stylesheets []
           :auto-reload? true}
   :menus [{:id :extensions
            :label "Extensions"
            :items [{:label "Fetch Libraries"
                     :command :project.fetch-libraries}
                    {:label "Reload Editor Scripts"
                   :command :project.reload-editor-scripts}
                  {:label "Reload Project Theme"
                   :command :editor-ui-tools.reload-theme}
                  {:separator true}
                  {:label "Open Logs"
                     :command :help.open-logs}
                    {:label "Open Editor Server"
                     :command :help.open-editor-server}]}]
   :tabs [{:id :extensions
           :label "Extensions"
           :area :bottom
           :type :actions
           :items [{:label "Fetch Libraries"
                    :command :project.fetch-libraries}
                   {:label "Reload Editor Scripts"
                   :command :project.reload-editor-scripts}
                   {:label "Reload Project Theme"
                    :command :editor-ui-tools.reload-theme}
                   {:label "Preferences"
                    :command :app.preferences}]}
          {:id :status
           :label "Extensions Status"
           :area :bottom
           :type :status}]
   :image-preview {:asset-browser-hover? true
                   :editor-tab? true
                   :extensions default-image-exts}})

(defonce ^:private current-config (atom default-config))
(defonce ^:private current-workspace (atom nil))

(defn- warn
  [message & kvs]
  (try
    (apply log/warn :message (str "Editor UI Tools: " message) kvs)
    (catch Throwable _
      (binding [*out* *err*]
        (println (str "Editor UI Tools: " message))))))

(defn- info
  [message & kvs]
  (try
    (apply log/info :message (str "Editor UI Tools: " message) kvs)
    (catch Throwable _
      (println (str "Editor UI Tools: " message)))))

(defn- project-file
  ^File [workspace path]
  (let [path (string/replace (str path) #"^/+" "")]
    (io/file (workspace/project-directory workspace) path)))

(defn- read-config
  [workspace]
  (let [file (project-file workspace config-file-name)]
    (if-not (.exists file)
      default-config
      (try
        (merge-with
          (fn [default user]
            (if (and (map? default) (map? user))
              (merge default user)
              user))
          default-config
          (edn/read-string (slurp file)))
        (catch Throwable t
          (warn (str "Could not read " config-file-name ". Defaults will be used.") :exception t)
          default-config)))))

(defn- bytes-from-resource
  ^bytes [resource]
  (with-open [input (io/input-stream resource)
              output (ByteArrayOutputStream.)]
    (io/copy input output)
    (.toByteArray output)))

(defn- command-keyword
  [command]
  (cond
    (keyword? command) command
    (and (string? command) (string/starts-with? command ":")) (edn/read-string command)
    (string? command) (keyword command)
    :else nil))

(defn- menu-item
  [item]
  (cond
    (:separator item)
    {:label :separator}

    (:items item)
    (cond-> {:label (:label item)
             :children (mapv menu-item (:items item))}
      (:id item) (assoc :id (:id item)))

    :else
    (cond-> {:label (:label item)}
      (:id item) (assoc :id (:id item))
      (:command item) (assoc :command (command-keyword (:command item)))
      (:user-data item) (assoc :user-data (:user-data item)))))

(defn- register-menus!
  [config]
  (handler/unregister! ::menus)
  (let [menus (->> (:menus config)
                   (mapv (fn [{:keys [id label items]}]
                           {:id (or id (keyword (string/lower-case (str label))))
                            :label label
                            :children (mapv menu-item items)})))]
    (when (seq menus)
      (handler/register-menu! ::menus :editor.app-view/menubar menus)
      (try
        (ui/invalidate-menubar-item! :editor.app-view/menubar)
        (catch Throwable _ nil)))))

(defn- stylesheet-uri
  [workspace stylesheet]
  (let [file (project-file workspace stylesheet)]
    (when (.exists file)
      (str (.toURI file)))))

(defn- apply-theme!
  [workspace config]
  (try
    (when-let [root (some-> (ui/main-stage) .getScene .getRoot)]
      (let [stylesheets (.getStylesheets ^Parent root)
            previous (or (ui/user-data root ::stylesheets) [])
            next-stylesheets (->> (get-in config [:theme :stylesheets])
                                  (keep (partial stylesheet-uri workspace))
                                  distinct
                                  vec)]
        (doseq [uri previous]
          (.remove stylesheets uri))
        (doseq [uri next-stylesheets]
          (when-not (.contains stylesheets uri)
            (.add stylesheets uri)))
        (ui/user-data! root ::stylesheets next-stylesheets)
        (ui/reload-root-styles!)))
    (catch Throwable t
      (warn "Could not apply project theme." :exception t))))

(defn- ensure-webp-imageio! []
  (try
    (let [webp-imageio-class (workspace/load-class! "com.defold.extension.pipeline.webp.WebPImageIO")
          install-method (.getMethod webp-imageio-class "install" (make-array Class 0))]
      (.invoke install-method nil (object-array 0)))
    (catch Throwable _ nil)))

(defn- webp-resource?
  [resource]
  (and (resource/resource? resource)
       (= "webp" (some-> (resource/type-ext resource) string/lower-case))))

(defn- webp-metadata
  [resource]
  (when (webp-resource? resource)
    (try
      (ensure-webp-imageio!)
      (let [webp-frames-class (workspace/load-class! "com.defold.extension.pipeline.webp.WebPFrames")
            read-method (.getMethod webp-frames-class "read" (into-array Class [(Class/forName "[B")]))
            decoded (.invoke read-method nil (object-array [(bytes-from-resource resource)]))
            decoded-class (.getClass decoded)
            frame-count (.invoke (.getMethod decoded-class "getFrameCount" (make-array Class 0)) decoded (object-array 0))
            fps (.invoke (.getMethod decoded-class "getAverageFps" (make-array Class 0)) decoded (object-array 0))
            animated? (.invoke (.getMethod decoded-class "isAnimated" (make-array Class 0)) decoded (object-array 0))]
        {:frame-count frame-count
         :fps fps
         :animated? animated?})
      (catch Throwable t
        {:error (.getMessage t)}))))

(defn- read-buffered-image
  ^BufferedImage [resource]
  (with-open [stream (io/input-stream resource)]
    (ImageIO/read stream)))

(defn- scale-buffered-image
  ^BufferedImage [^BufferedImage source max-width max-height flip-y?]
  (let [width (.getWidth source)
        height (.getHeight source)
        scale (min 1.0
                   (double (/ max-width (max 1 width)))
                   (double (/ max-height (max 1 height))))
        target-width (max 1 (int (Math/round (* width scale))))
        target-height (max 1 (int (Math/round (* height scale))))
        target (BufferedImage. target-width target-height BufferedImage/TYPE_INT_ARGB)
        graphics (.createGraphics target)]
    (try
      (.setRenderingHint graphics RenderingHints/KEY_INTERPOLATION RenderingHints/VALUE_INTERPOLATION_BILINEAR)
      (if flip-y?
        (.drawImage graphics source 0 target-height target-width (- target-height) nil)
        (.drawImage graphics source 0 0 target-width target-height nil))
      target
      (finally
        (.dispose graphics)))))

(defn- fx-image
  (^Image [resource max-width max-height]
   (fx-image resource max-width max-height false))
  (^Image [resource max-width max-height flip-y?]
   (ensure-webp-imageio!)
   (if-let [buffered-image (read-buffered-image resource)]
     (let [scaled (scale-buffered-image buffered-image max-width max-height flip-y?)
           width (.getWidth scaled)
           height (.getHeight scaled)
           image (WritableImage. width height)
           writer (.getPixelWriter image)]
       (doseq [y (range height)
               x (range width)]
         (.setArgb writer x y (.getRGB scaled x y)))
       image)
     (throw (ex-info "ImageIO could not decode the image." {:resource resource})))))

(g/defnode ImagePreviewNode
  (property image Image))

(g/defnode ImagePreviewView
  (inherits view/WorkbenchView)
  (property parent g/Any))

(defn- make-preview
  [graph resource-node _opts width height]
  (let [resource (g/node-value resource-node :resource)
        image (fx-image resource width height true)]
    (first
      (g/tx-nodes-added
        (g/transact
          (g/make-node graph ImagePreviewNode :image image))))))

(defn- make-image-preview-content
  ^Node [resource]
  (let [image (fx-image resource 4096 4096)
        image-view (doto (ImageView. image)
                     (.setPreserveRatio true)
                     (.setSmooth false))
        scroll-pane (doto (ScrollPane. image-view)
                      (.setFitToWidth true)
                      (.setFitToHeight true)
                      (ui/add-style! "editor-ui-tools-image-preview"))
        header (doto (Label. (or (resource/proj-path resource) (resource/resource-name resource)))
                 (.setFont (Font/font "System" FontWeight/BOLD 13.0)))
        webp-meta (webp-metadata resource)
        meta-text (cond
                    (:error webp-meta)
                    (format "%d x %d | WebP metadata unavailable: %s"
                            (int (.getWidth image))
                            (int (.getHeight image))
                            (:error webp-meta))

                    webp-meta
                    (format "%d x %d | WebP %s | %s frame(s) | %s fps"
                            (int (.getWidth image))
                            (int (.getHeight image))
                            (if (:animated? webp-meta) "animated" "static")
                            (:frame-count webp-meta)
                            (:fps webp-meta))

                    :else
                    (format "%d x %d" (int (.getWidth image)) (int (.getHeight image))))
        meta (Label. meta-text)
        box (doto (VBox. 8.0)
              (.setAlignment Pos/TOP_LEFT))]
    (VBox/setVgrow scroll-pane Priority/ALWAYS)
    (.addAll (.getChildren box) ^Collection [header meta scroll-pane])
    box))

(defn- make-image-view
  [graph ^Parent parent resource-node _opts]
  (let [resource (g/node-value resource-node :resource)
        view-node (first
                    (g/tx-nodes-added
                      (g/transact
                        (g/make-node graph ImagePreviewView :parent parent))))
        content (try
                  (make-image-preview-content resource)
                  (catch Throwable t
                    (warn "Could not create image preview tab." :exception t)
                    (Label. (.getMessage t))))]
    (AnchorPane/setTopAnchor content 0.0)
    (AnchorPane/setRightAnchor content 0.0)
    (AnchorPane/setBottomAnchor content 0.0)
    (AnchorPane/setLeftAnchor content 0.0)
    (.setAll (.getChildren ^AnchorPane parent) ^Collection [content])
    view-node))

(defn- image-exts
  [config]
  (set (map (comp string/lower-case str)
            (get-in config [:image-preview :extensions] default-image-exts))))

(defn- image-resource?
  [config item]
  (and (resource/resource? item)
       (contains? (image-exts config) (resource/type-ext item))))

(defn- add-image-preview-view-type
  [resource-type image-view-type]
  (let [view-types (vec (:view-types resource-type))]
    (if (some #(= image-preview-view-id (:id %)) view-types)
      resource-type
      (assoc resource-type
             :editor-openable true
             :view-types (vec (cons image-view-type view-types))))))

(defn- enable-existing-image-resource-types!
  [workspace config]
  (when (get-in config [:image-preview :editor-tab?])
    (let [image-view-type (workspace/get-view-type workspace image-preview-view-id)
          exts (image-exts config)]
      (g/transact
        (g/update-property workspace :resource-types
                           (fn [resource-types]
                             (reduce
                               (fn [resource-types ext]
                                 (if (contains? resource-types ext)
                                   (update resource-types ext add-image-preview-view-type image-view-type)
                                   resource-types))
                               resource-types
                               exts)))))))

(defn- include-preview-view-type-kv
  [kvs]
  (if-not (get-in @current-config [:image-preview :editor-tab?])
    kvs
    (let [m (apply hash-map kvs)
          exts (if (coll? (:ext m)) (:ext m) [(:ext m)])
          target? (some (image-exts @current-config) (map (comp string/lower-case str) exts))]
      (if-not target?
        kvs
        (let [view-types (vec (:view-types m))]
          (mapcat identity
                  (assoc m :view-types
                           (if (some #{image-preview-view-id} view-types)
                             view-types
                             (vec (cons image-preview-view-id view-types))))))))))

(defn- wrap-register-resource-type! []
  (let [register-resource-type-var #'workspace/register-resource-type]
    (when-not (::wrapped (meta register-resource-type-var))
      (alter-var-root register-resource-type-var
                      (fn [register-resource-type]
                        (fn [workspace & kvs]
                          (apply register-resource-type workspace (include-preview-view-type-kv kvs)))))
      (alter-meta! register-resource-type-var assoc ::wrapped true))))

(defn- preview-tooltip
  [item]
  {:fx/type fx.tooltip/lifecycle
   :graphic {:fx/type fx.image-view/lifecycle
             :preserve-ratio true
             :smooth false}
   :on-showing (fn [^Event e]
                 (let [^Tooltip tooltip (.getSource e)
                       ^ImageView image-view (.getGraphic tooltip)]
                   (when-not (.getImage image-view)
                     (try
                       (.setImage image-view (fx-image item 256 256))
                       (catch Throwable t
                         (warn "Could not show image hover preview." :exception t))))))})

(defn- wrap-asset-browser-preview! []
  (let [describe-tree-cell-var (ns-resolve 'editor.asset-browser 'describe-tree-cell)]
    (when (and describe-tree-cell-var
               (not (::wrapped (meta describe-tree-cell-var))))
      (alter-var-root describe-tree-cell-var
                      (fn [describe-tree-cell]
                        (fn [localization-state on-drag-dropped item]
                          (let [config @current-config
                                desc (describe-tree-cell localization-state on-drag-dropped item)]
                            (if (and (get-in config [:image-preview :asset-browser-hover?])
                                     (image-resource? config item))
                              (assoc desc :tooltip (preview-tooltip item))
                              desc)))))
      (alter-meta! describe-tree-cell-var assoc ::wrapped true))))

(defn- read-text-resource
  [workspace path]
  (let [file (project-file workspace path)]
    (if (.exists file)
      (slurp file)
      (str "Missing file: " path))))

(defn- run-command!
  [^Node node command]
  (when-let [command (command-keyword command)]
    (ui/run-command node command)))

(defn- make-actions-tab-content
  [items]
  (let [box (doto (VBox. 8.0)
              (.setAlignment Pos/TOP_LEFT)
              (ui/add-style! "editor-ui-tools-tab"))]
    (doseq [{:keys [label command separator]} items]
      (if separator
        (let [line (Region.)]
          (.setPrefHeight line 1.0)
          (ui/add-style! line "editor-ui-tools-separator")
          (.add (.getChildren box) line))
        (let [button (Button. (str label))]
          (.setMaxWidth button Double/MAX_VALUE)
          (.setOnAction button (ui/event-handler _ (run-command! button command)))
          (.add (.getChildren box) button))))
    box))

(defn- make-text-tab-content
  [workspace {:keys [resource text]}]
  (let [label (doto (Label. (or text (read-text-resource workspace resource)))
                (.setWrapText true))
        scroll (ScrollPane. label)]
    (.setFitToWidth scroll true)
    scroll))

(defn- status-line
  [label value]
  (let [row (HBox. 8.0)
        label-node (doto (Label. label)
                     (.setFont (Font/font "System" FontWeight/BOLD 12.0)))
        value-node (doto (Label. (str value))
                     (.setWrapText true))]
    (.addAll (.getChildren row) ^Collection [label-node value-node])
    row))

(defn- make-status-tab-content
  [workspace config]
  (let [box (doto (VBox. 8.0)
              (.setAlignment Pos/TOP_LEFT)
              (ui/add-style! "editor-ui-tools-tab"))
        theme-files (or (seq (get-in config [:theme :stylesheets])) ["<none>"])
        image-exts (string/join ", " (image-exts config))]
    (.addAll (.getChildren box)
             ^Collection
             [(status-line "Config" (str "/" config-file-name))
              (status-line "Theme" (string/join ", " theme-files))
              (status-line "Menus" (count (:menus config)))
              (status-line "Tabs" (count (:tabs config)))
              (status-line "Image Preview" image-exts)
              (status-line "Project" (.getPath (workspace/project-directory workspace)))])
    box))

(defn- make-custom-tab-content
  [workspace tab-config]
  (case (:type tab-config)
    :status (make-status-tab-content workspace @current-config)
    :text (make-text-tab-content workspace tab-config)
    :actions (make-actions-tab-content (:items tab-config))
    (make-actions-tab-content (:items tab-config))))

(defn- ensure-bottom-tabs!
  [workspace config ^TabPane tool-tab-pane]
  (doseq [tab-config (filter #(= :bottom (:area % :bottom)) (:tabs config))]
    (let [tab-id (str "editor-ui-tools-" (name (:id tab-config)))]
      (if-let [existing-tab (first (filter #(= tab-id (.getId ^Tab %)) (.getTabs tool-tab-pane)))]
        (.setContent ^Tab existing-tab (make-custom-tab-content workspace tab-config))
        (let [tab (doto (Tab. (str (:label tab-config)))
                    (.setId tab-id)
                    (.setClosable false)
                    (.setContent (make-custom-tab-content workspace tab-config)))]
          (.add (.getTabs tool-tab-pane) tab))))))

(defn- ensure-editor-tabs!
  [workspace config ^TabPane editor-tab-pane]
  (doseq [tab-config (filter #(= :editor (:area %)) (:tabs config))]
    (let [tab-id (str "editor-ui-tools-" (name (:id tab-config)))]
      (if-let [existing-tab (first (filter #(= tab-id (.getId ^Tab %)) (.getTabs editor-tab-pane)))]
        (.setContent ^Tab existing-tab (make-custom-tab-content workspace tab-config))
        (let [tab (doto (Tab. (str (:label tab-config)))
                    (.setId tab-id)
                    (.setClosable true)
                    (.setContent (make-custom-tab-content workspace tab-config)))]
          (ui/user-data! tab protected-tab-key true)
          (.add (.getTabs editor-tab-pane) tab))))))

(defn- protected-tab?
  [^Tab tab]
  (boolean (ui/user-data tab protected-tab-key)))

(defn- wrap-remove-invalid-tabs! []
  (let [remove-invalid-tabs-var (ns-resolve 'editor.app-view 'remove-invalid-tabs!)]
    (when (and remove-invalid-tabs-var
               (not (::wrapped (meta remove-invalid-tabs-var))))
      (alter-var-root remove-invalid-tabs-var
                      (fn [remove-invalid-tabs]
                        (fn [tab-panes open-views]
                          (let [protected (vec
                                            (for [^TabPane pane tab-panes
                                                  :let [tabs (vec (.getTabs pane))
                                                        protected-tabs (filterv protected-tab? tabs)]
                                                  :when (seq protected-tabs)]
                                              [pane protected-tabs]))]
                            (doseq [[^TabPane pane tabs] protected
                                    ^Tab tab tabs]
                              (.remove (.getTabs pane) tab))
                            (remove-invalid-tabs tab-panes open-views)
                            (doseq [[^TabPane pane tabs] protected
                                    ^Tab tab tabs]
                              (when-not (.contains (.getTabs pane) tab)
                                (.add (.getTabs pane) tab)))))))
      (alter-meta! remove-invalid-tabs-var assoc ::wrapped true))))

(defn- wrap-make-app-view! []
  (let [make-app-view-var #'editor.app-view/make-app-view]
    (when-not (::wrapped (meta make-app-view-var))
      (alter-var-root make-app-view-var
                      (fn [make-app-view]
                        (fn [view-graph project stage menu-bar editor-tabs-split right-split tool-tab-pane prefs localization]
                          (let [result (make-app-view view-graph project stage menu-bar editor-tabs-split right-split tool-tab-pane prefs localization)
                                workspace @current-workspace
                                config @current-config
                                editor-tab-pane (first (.getItems ^javafx.scene.control.SplitPane editor-tabs-split))]
                            (apply-theme! workspace config)
                            (ensure-bottom-tabs! workspace config tool-tab-pane)
                            (when editor-tab-pane
                              (ensure-editor-tabs! workspace config editor-tab-pane))
                            result))))
      (alter-meta! make-app-view-var assoc ::wrapped true))))

(defn- register-view-types!
  [workspace]
  (g/transact
    (workspace/register-view-type workspace
                                  :id image-preview-view-id
                                  :label "Image Preview"
                                  :make-view-fn #'make-image-view
                                  :make-preview-fn #'make-preview)))

(defn- reload-config-and-theme! []
  (when-let [workspace @current-workspace]
    (let [config (read-config workspace)]
      (reset! current-config config)
      (register-menus! config)
      (apply-theme! workspace config)
      (info "Project theme reloaded."))))

(handler/defhandler :editor-ui-tools.reload-theme :workbench
  :label "Reload Project Theme"
  (run []
    (reload-config-and-theme!)))

(defn- load-plugin
  [workspace]
  (let [config (read-config workspace)]
    (reset! current-workspace workspace)
    (reset! current-config config)
    (register-view-types! workspace)
    (enable-existing-image-resource-types! workspace config)
    (wrap-register-resource-type!)
    (wrap-asset-browser-preview!)
    (wrap-remove-invalid-tabs!)
    (wrap-make-app-view!)
    (register-menus! config)
    (apply-theme! workspace config)
    (info (format "Loaded %d menu(s), %d tab(s), %d stylesheet(s)."
                  (count (:menus config))
                  (count (:tabs config))
                  (count (get-in config [:theme :stylesheets]))))))

(fn [workspace]
  (load-plugin workspace))
