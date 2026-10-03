package com.github.grepHammerspace.web;

import java.util.List;

public record OtjSubmitResult(List<String> posted, List<String> failed) {}
