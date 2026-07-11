package com.github.grepHammerspace.llm;

import com.github.grepHammerspace.db.model.ActivityLog;

import java.util.List;

public interface KsbMatcherService {
    KsbMatchResult matchActivitiesToKsbs(List<ActivityLog> logs, String userId, String learnerId);
}
