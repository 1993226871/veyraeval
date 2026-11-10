CREATE TABLE sdk_projects (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    name VARCHAR(100) NOT NULL,
    secret_hash CHAR(64) NOT NULL UNIQUE,
    auto_evaluate BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE(workspace_id, name),
    UNIQUE(id, workspace_id)
);

CREATE TABLE sdk_datasets (
    project_id UUID NOT NULL REFERENCES sdk_projects(id),
    namespace VARCHAR(100) NOT NULL,
    version VARCHAR(128) NOT NULL,
    dataset_version_id UUID NOT NULL REFERENCES catalog_versions(id),
    content_hash CHAR(64) NOT NULL,
    PRIMARY KEY(project_id, namespace, version)
);

CREATE TABLE sdk_runs (
    experiment_id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    client_run_id UUID NOT NULL,
    request_hash CHAR(64) NOT NULL,
    namespace VARCHAR(100) NOT NULL,
    name VARCHAR(200) NOT NULL,
    authorized BOOLEAN NOT NULL,
    UNIQUE(project_id, client_run_id),
    FOREIGN KEY(experiment_id, workspace_id) REFERENCES experiments(id, workspace_id),
    FOREIGN KEY(project_id, workspace_id) REFERENCES sdk_projects(id, workspace_id)
);
