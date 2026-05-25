{ pkgs ? import <nixpkgs> {} }:

pkgs.mkShell {
  buildInputs = with pkgs; [
    docker-compose
    curl
    jq
  ];

  shellHook = ''
    # Must be run from the deploy/ directory
    DEPLOY_DIR="$PWD"
    ENV_FILE="$DEPLOY_DIR/../.env"

    if [ -f "$ENV_FILE" ]; then
      set -a
      source "$ENV_FILE"
      set +a
      echo "Loaded env from .env"
    else
      echo "Warning: .env not found at $ENV_FILE"
    fi

    clean-mongo() {
      echo "Stopping containers and removing Mongo data volume..."
      docker compose --file "$DEPLOY_DIR/docker-compose.yml" down -v
      echo "Done. Mongo data wiped."
    }
    export -f clean-mongo

    echo ""
    echo "otj dev shell ready"
    echo "  ./bootstrap.sh   start mongo + mongo-express"
    echo "  clean-mongo      stop containers and wipe Mongo data"
    echo ""
  '';
}
