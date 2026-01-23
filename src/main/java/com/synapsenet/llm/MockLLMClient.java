package com.synapsenet.llm;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!gemini")
public class MockLLMClient implements LLMClient{
	
	@Override
	public String generate ( String prompt) {
		return """
				 Step 1: Analyze the task
				 Step 2: Break it into sub-tasks
				 Step 3: Execute step by step
				
				""";
	}
}
