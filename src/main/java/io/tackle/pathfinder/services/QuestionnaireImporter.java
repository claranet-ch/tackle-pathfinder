/*
 * Copyright © 2021 the Konveyor Contributors (https://konveyor.io/)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.tackle.pathfinder.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import io.tackle.pathfinder.model.QuestionType;
import io.tackle.pathfinder.model.Risk;
import io.tackle.pathfinder.model.questionnaire.Category;
import io.tackle.pathfinder.model.questionnaire.Question;
import io.tackle.pathfinder.model.questionnaire.Questionnaire;
import io.tackle.pathfinder.model.questionnaire.SingleOption;
import org.wildfly.common.Assert;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import javax.transaction.Transactional;
import java.util.ArrayList;
import java.util.Objects;
import java.util.regex.Pattern;

@ApplicationScoped
public class QuestionnaireImporter {

    private static final String CHOICE_ORDER = "choiceNr";
    private static final String CHOICE_RISK = "risk";
    private static final String CHOICE_TEXT = "text";
    private static final Pattern CHOICE_PATTERN = Pattern.compile("(?<"+ CHOICE_ORDER +">\\d+)-(?<"+CHOICE_RISK+">[A-Z]+)\\|(?<"+CHOICE_TEXT+">.*)");

    @Inject
    ObjectMapper objectMapper;

    @Transactional
    void onStart(@Observes StartupEvent ev) {
        //application started.
        try (var stream = getClass().getResourceAsStream("/schemas/custom-pathfinder-questionnaire.json")) {
            var rootNode = objectMapper.readTree(stream);
            Assert.assertTrue(rootNode.isObject());
            var jsonVersion = Objects.requireNonNull(rootNode.get("version")).textValue();
            var existingQuestionnaire = Questionnaire.findAll().<Questionnaire>firstResult();
            var categoryIdsToKeep = new ArrayList<Long>();
            if (!existingQuestionnaire.version.equals(jsonVersion)) {
                var pages = ((ObjectNode) rootNode).withArray("pages");
                for (int i=0; i < pages.size(); i++) {
                    categoryIdsToKeep.add(processPage((ObjectNode) pages.get(i), i+1, existingQuestionnaire));
                }
                long result = Category.delete("questionnaire.id = ?1 and id not in (?2)", existingQuestionnaire.id, categoryIdsToKeep);
                Log.debugf("deleted %v categories", result);
                existingQuestionnaire.version = jsonVersion;
            } else {
                Log.info("skipping migration. Version "+ jsonVersion + " already exists.");
            }
        } catch (Exception ex) {
            Log.error("error while parsing JSON", ex);
        }
    }

    private static long processPage(ObjectNode page, int order, Questionnaire questionnaire) {
        var category = Category.<Category>find("slug = ?1 and questionnaire.id = ?2", page.get("slug").asText(), questionnaire.id)
            .firstResultOptional()
            .orElseGet(Category::new);

        // base settings for category

        category.name = page.get("title").asText();
        category.order = order;
        category.deleted = false;
        
        // questions

        var questions = page.withArray("questions");
        var questionIdsToKeep = new ArrayList<Long>();
        for (int i = 0; i < questions.size(); i++) {
            var jsonQuestion = (ObjectNode) questions.get(i);
            var name = jsonQuestion.get("name").asText();
            var existingQuestion = Question.<Question>find("category_id = ?1 and name = ?2", category.id, name)
                .firstResultOptional().orElseGet(Question::new);
            existingQuestion.category = category;
            existingQuestion.name = name;
            existingQuestion.questionText = jsonQuestion.get("title").asText();
            existingQuestion.description = jsonQuestion.get("tooltip").asText();
            existingQuestion.type = QuestionType.SINGLE;
            existingQuestion.persist();
            questionIdsToKeep.add(existingQuestion.id);
            updateQuestion(jsonQuestion, existingQuestion);
        }
        var inactiveOptions = SingleOption.<SingleOption>list("question.category.id = ?1 and question.id not in (?2)", category.id, questionIdsToKeep);
        inactiveOptions.forEach(SingleOption::delete);
        long result = Question.update("set deleted = true where category.id = ?1 and id not in (?2)", category.id, questionIdsToKeep);
        Log.infof("deleted %d questions and %d related options", result, inactiveOptions.size());
        return category.id;
    }

    private static void updateQuestion(ObjectNode json, Question entity) {
        entity.deleted = false;
        var choices = json.withArray("choices");
        var choicesToKeep = new ArrayList<Long>();
        for (int i = 0; i < choices.size(); i++) {
            var choice = choices.get(i).textValue();
            var matcher = CHOICE_PATTERN.matcher(choice);
            if (matcher.matches()) {
                int choiceOrder = Integer.parseInt(matcher.group(CHOICE_ORDER));
                var choiceRisk = Risk.valueOf(matcher.group(CHOICE_RISK));
                var text = matcher.group(CHOICE_TEXT);
                SingleOption existingOption = SingleOption.<SingleOption>find("question_id = ?1 and singleoption_order = ?2", entity.id, choiceOrder)
                        .firstResultOptional().orElseGet(SingleOption::new);
                existingOption.order = choiceOrder;
                existingOption.risk = choiceRisk;
                existingOption.option = text;
                existingOption.question = entity;
                existingOption.persist();
                choicesToKeep.add(existingOption.id);
            }
        }
        long result = SingleOption.update("set deleted = true where question.id = ?1 and id not in (?2)", entity.id, choicesToKeep);
        Log.infof("Deleted %d single_options", result);
    }
}
