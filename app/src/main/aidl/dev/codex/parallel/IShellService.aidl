package dev.codex.parallel;

interface IShellService {
    String run(in String[] args) = 0;
    void destroy() = 16777114;
}
