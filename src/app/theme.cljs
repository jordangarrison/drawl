(ns app.theme
  "Light/dark/system theme controller.

  Three preferences, cycled by the toggle button:
    :system → follow prefers-color-scheme (default; no localStorage entry)
    :light  → force Rosé Pine Dawn
    :dark   → force Rosé Pine

  The current preference is a single value held in `*preference`,
  initialised from localStorage. `render!` is the only effect edge:
  it writes [data-theme] on <html>, refreshes the toggle button, and
  notifies the listener (used to swap CodeMirror). The DOM and the
  button are projections of the value, not the source of truth.")

(def ^:private storage-key "drawl/theme")
(def ^:private dark-query "(prefers-color-scheme: dark)")

(def ^:private next-pref {:system :light, :light :dark, :dark :system})

(defn- read-pref []
  (try
    (when-let [raw (.getItem (.-localStorage js/window) storage-key)]
      (let [k (keyword raw)]
        (when (next-pref k) k)))
    (catch :default _ nil)))

(defn- write-pref! [pref]
  (try
    (let [ls (.-localStorage js/window)]
      (if (= :system pref)
        (.removeItem ls storage-key)
        (.setItem ls storage-key (name pref))))
    (catch :default _ nil)))

(defn- system-active []
  (if (.-matches (.matchMedia js/window dark-query)) :dark :light))

(defn- resolve-active [pref]
  (case pref :system (system-active) pref))

(defn- glyph [pref]
  (case pref :light "☼" :dark "☾" :system "◐"))

(defn- title [pref]
  (str "Theme: " (name pref) " (click to cycle)"))

(defonce ^:private *preference (atom (or (read-pref) :system)))

(defn- render!
  "The single side-effect edge. Derives the active palette from `pref`,
  writes [data-theme], refreshes the toggle button, and notifies
  `on-change` with the active palette."
  [pref on-change]
  (let [active (resolve-active pref)]
    (set! (.. js/document -documentElement -dataset -theme) (name active))
    (when-let [btn (.getElementById js/document "theme-toggle")]
      (set! (.-textContent btn) (glyph pref))
      (.setAttribute btn "title" (title pref))
      (.setAttribute btn "aria-label" (title pref)))
    (when on-change (on-change active))
    active))

(defn current-active-mode
  "Pure: the active palette (:light/:dark) for the current preference.
  Lets callers bootstrap external state (e.g. the editor) before
  init! runs render!."
  []
  (resolve-active @*preference))

(defn cycle!
  "Advance preference (system → light → dark → system…), persist, render."
  [on-change]
  (let [next (next-pref @*preference)]
    (reset! *preference next)
    (write-pref! next)
    (render! next on-change)))

(defn- wire-toggle! [on-change]
  (when-let [btn (.getElementById js/document "theme-toggle")]
    (.addEventListener btn "click" #(cycle! on-change))))

(defn- wire-system-follow!
  "Re-render on OS-level changes whenever the preference is :system."
  [on-change]
  (.addEventListener (.matchMedia js/window dark-query) "change"
                     (fn [_]
                       (when (= :system @*preference)
                         (render! :system on-change)))))

(defn init!
  "Apply the resolved preference, wire the toggle button, and follow
  OS-level changes when no explicit preference is set. Returns the
  active palette (:light/:dark) so the caller can stay in sync."
  [{:keys [on-change]}]
  (wire-toggle! on-change)
  (wire-system-follow! on-change)
  (render! @*preference on-change))
