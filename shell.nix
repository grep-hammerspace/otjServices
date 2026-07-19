{ pkgs ? import (builtins.fetchTarball {
    url = "https://channels.nixos.org/nixos-25.11/nixexprs.tar.xz";
  }) {} }:

pkgs.mkShell {
  buildInputs = with pkgs; [
    jdk25
    maven
    podman
    podman-compose
    nodejs
    nodePackages.aws-cdk
    awscli2
    ssm-session-manager-plugin
  ];

  JAVA_HOME = pkgs.jdk25.home;

  shellHook = ''
    echo ""
    echo "otjServices dev shell ready"
    echo "  java   $(java -version 2>&1 | head -n1)"
    echo "  mvn    $(mvn -v | head -n1)"
    echo "  podman $(podman --version)"
    echo "  cdk    $(cdk --version)"
    echo "  aws    $(aws --version)"
    echo ""
    echo "  mvn package -DskipTests   build the jar"
    echo "  mvn test                  run unit tests"
    echo "  cd deploy && nix-shell    local podman-compose stack (mongo, mongo-express, app)"
    echo ""
  '';
}
