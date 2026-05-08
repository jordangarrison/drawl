{ pkgs }:

let
  jdk = pkgs.jdk21;
in
pkgs.mkShell {
  buildInputs = with pkgs; [
    jdk
    clojure
    babashka
    bbin
    nodejs_22
    clj-kondo
    graphviz
    mermaid-cli
  ];

  shellHook = ''
    export JAVA_HOME=${jdk}

    # Install bbin tools into a repo-local prefix so they don't
    # pollute ~/.local/bin. bbin uses deps.clj under the hood,
    # which wants to write CLJ_CONFIG (read-only on nix-managed
    # setups) — redirect that locally too.
    export BABASHKA_BBIN_BIN_DIR="$PWD/.bbin/bin"
    export BABASHKA_BBIN_JARS_DIR="$PWD/.bbin/jars"
    export CLJ_CONFIG="$PWD/.bbin/clojure"
    export DEPS_CLJ_TOOLS_DIR="$PWD/.bbin/deps-clj"
    export PATH="$BABASHKA_BBIN_BIN_DIR:$PATH"
    mkdir -p "$BABASHKA_BBIN_BIN_DIR" "$BABASHKA_BBIN_JARS_DIR" "$CLJ_CONFIG" "$DEPS_CLJ_TOOLS_DIR"

    if [ ! -x "$BABASHKA_BBIN_BIN_DIR/clj-nrepl-eval" ]; then
      echo "drawl: installing clj-nrepl-eval (bbin, clojure-mcp-light v0.2.2)…"
      bbin install \
        https://github.com/bhauman/clojure-mcp-light.git --tag v0.2.2 \
        --as clj-nrepl-eval \
        --main-opts '["-m" "clojure-mcp-light.nrepl-eval"]' \
        >/dev/null
    fi
  '';
}
