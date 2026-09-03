# Dev container

Opens a ready-to-build workspace on **Ubuntu 26.04 LTS**: Temurin JDK 25 + Maven,
Node 22, `just`, and a Docker daemon (docker-in-docker) so `just up` works from
inside.

- **VS Code**: "Dev Containers: Reopen in Container".
- **CLI**: `devcontainer up --workspace-folder .`

`onCreateCommand` pre-fetches npm and Maven dependencies. After it opens:

```
just verify      # backend + frontend
just up          # full dev stack
```
