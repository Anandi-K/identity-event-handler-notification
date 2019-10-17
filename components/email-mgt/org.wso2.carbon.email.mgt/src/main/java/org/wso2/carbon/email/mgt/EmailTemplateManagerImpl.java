/*
 * Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wso2.carbon.email.mgt;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.email.mgt.constants.I18nMgtConstants;
import org.wso2.carbon.email.mgt.exceptions.I18nEmailMgtClientException;
import org.wso2.carbon.email.mgt.exceptions.I18nEmailMgtException;
import org.wso2.carbon.email.mgt.exceptions.I18nEmailMgtInternalException;
import org.wso2.carbon.email.mgt.exceptions.I18nEmailMgtServerException;
import org.wso2.carbon.email.mgt.exceptions.DuplicateEmailTemplateException;
import org.wso2.carbon.email.mgt.internal.I18nMgtDataHolder;
import org.wso2.carbon.email.mgt.model.EmailTemplate;
import org.wso2.carbon.email.mgt.util.I18nEmailUtil;
import org.wso2.carbon.identity.base.IdentityRuntimeException;
import org.wso2.carbon.identity.base.IdentityValidationUtil;
import org.wso2.carbon.identity.core.persistence.registry.RegistryResourceMgtService;
import org.wso2.carbon.registry.core.Collection;
import org.wso2.carbon.registry.core.Resource;
import org.wso2.carbon.registry.core.exceptions.RegistryException;

import java.util.ArrayList;
import java.util.List;

import static org.wso2.carbon.email.mgt.constants.I18nMgtConstants.DEFAULT_EMAIL_LOCALE;
import static org.wso2.carbon.email.mgt.constants.I18nMgtConstants.EMAIL_TEMPLATE_NAME;
import static org.wso2.carbon.email.mgt.constants.I18nMgtConstants.EMAIL_TEMPLATE_PATH;
import static org.wso2.carbon.email.mgt.constants.I18nMgtConstants.EMAIL_TEMPLATE_TYPE_DISPLAY_NAME;
import static org.wso2.carbon.email.mgt.constants.I18nMgtConstants.EMAIL_TEMPLATE_TYPE_REGEX;
import static org.wso2.carbon.email.mgt.constants.I18nMgtConstants.ErrorMsg.DUPLICATE_TEMPLATE_TYPE;
import static org.wso2.carbon.identity.base.IdentityValidationUtil.ValidatorPattern.REGISTRY_INVALID_CHARS_EXISTS;
import static org.wso2.carbon.registry.core.RegistryConstants.PATH_SEPARATOR;

/**
 * Provides functionality to manage email templates used in notification emails.
 */
public class EmailTemplateManagerImpl implements EmailTemplateManager {
    private I18nMgtDataHolder dataHolder = I18nMgtDataHolder.getInstance();
    private RegistryResourceMgtService resourceMgtService = dataHolder.getRegistryResourceMgtService();

    private static final Log log = LogFactory.getLog(EmailTemplateManagerImpl.class);

    private static final String TEMPLATE_REGEX_KEY = I18nMgtConstants.class.getName() + "_" + EMAIL_TEMPLATE_NAME;
    private static final String REGISTRY_INVALID_CHARS = I18nMgtConstants.class.getName() + "_" + "registryInvalidChar";

    static {
        IdentityValidationUtil.addPattern(TEMPLATE_REGEX_KEY, EMAIL_TEMPLATE_TYPE_REGEX);
        IdentityValidationUtil.addPattern(REGISTRY_INVALID_CHARS, REGISTRY_INVALID_CHARS_EXISTS.getRegex());
    }

    @Override
    public void addEmailTemplateType(String emailTemplateDisplayName, String tenantDomain) throws
            I18nEmailMgtException {

        validateEmailTemplateType(emailTemplateDisplayName, tenantDomain);

        // get template directory name from display name.
        String normalizedTemplateName = I18nEmailUtil.getNormalizedName(emailTemplateDisplayName);

        // persist the template type to registry ie. create a directory.
        String path = EMAIL_TEMPLATE_PATH + PATH_SEPARATOR + normalizedTemplateName;
        try {
            // check whether a template exists with the same name.
            if (resourceMgtService.isResourceExists(path, tenantDomain)) {
                String errorMsg = String.format(DUPLICATE_TEMPLATE_TYPE, emailTemplateDisplayName, tenantDomain);
                throw new I18nEmailMgtInternalException(I18nMgtConstants.ErrorCodes.EMAIL_TEMPLATE_TYPE_ALREADY_EXISTS,
                        errorMsg);
            }

            Collection collection = I18nEmailUtil.createTemplateType(normalizedTemplateName, emailTemplateDisplayName);
            resourceMgtService.putIdentityResource(collection, path, tenantDomain);
        } catch (IdentityRuntimeException ex) {
            String errorMsg = "Error adding template type %s to %s tenant.";
            throw new I18nEmailMgtServerException(String.format(errorMsg, emailTemplateDisplayName, tenantDomain), ex);
        }
    }

    @Override
    public void deleteEmailTemplateType(String emailTemplateDisplayName, String tenantDomain) throws
            I18nEmailMgtException {

        validateEmailTemplateType(emailTemplateDisplayName, tenantDomain);

        String templateType = I18nEmailUtil.getNormalizedName(emailTemplateDisplayName);
        String path = EMAIL_TEMPLATE_PATH + PATH_SEPARATOR + templateType;

        try {
            resourceMgtService.deleteIdentityResource(path, tenantDomain);
        } catch (IdentityRuntimeException ex) {
            String errorMsg = String.format
                    ("Error deleting email template type %s from %s tenant.", emailTemplateDisplayName, tenantDomain);
            handleServerException(errorMsg, ex);
        }
    }

    /**
     * @param tenantDomain
     * @return
     * @throws I18nEmailMgtServerException
     */
    @Override
    public List<String> getAvailableTemplateTypes(String tenantDomain) throws I18nEmailMgtServerException {

        try {
            List<String> templateTypeList = new ArrayList<>();
            Collection collection = (Collection) resourceMgtService.getIdentityResource(EMAIL_TEMPLATE_PATH,
                    tenantDomain);

            for (String templatePath : collection.getChildren()) {
                Resource templateTypeResource = resourceMgtService.getIdentityResource(templatePath, tenantDomain);
                if (templateTypeResource != null) {
                    String emailTemplateType = templateTypeResource.getProperty(EMAIL_TEMPLATE_TYPE_DISPLAY_NAME);
                    templateTypeList.add(emailTemplateType);
                }
            }
            return templateTypeList;
        } catch (IdentityRuntimeException | RegistryException ex) {
            String errorMsg = String.format("Error when retrieving email template types of %s tenant.", tenantDomain);
            throw new I18nEmailMgtServerException(errorMsg, ex);
        }
    }

    @Override
    public List<EmailTemplate> getAllEmailTemplates(String tenantDomain) throws I18nEmailMgtException {

        List<EmailTemplate> templateList = new ArrayList<>();

        try {
            Collection baseDirectory = (Collection) resourceMgtService.getIdentityResource(EMAIL_TEMPLATE_PATH,
                    tenantDomain);

            if (baseDirectory != null) {
                for (String templateTypeDirectory : baseDirectory.getChildren()) {
                    Collection templateType = (Collection) resourceMgtService.getIdentityResource(templateTypeDirectory,
                            tenantDomain);
                    if (templateType != null) {
                        for (String template : templateType.getChildren()) {
                            Resource templateResource = resourceMgtService.getIdentityResource(template, tenantDomain);
                            if (templateResource != null) {
                                try {
                                    EmailTemplate templateDTO = I18nEmailUtil.getEmailTemplate(templateResource);
                                    templateList.add(templateDTO);
                                } catch (I18nEmailMgtException ex) {
                                    log.error(ex.getMessage(), ex);
                                }
                            }
                        }
                    }
                }
            }
        } catch (RegistryException | IdentityRuntimeException e) {
            String error = String.format("Error when retrieving email templates of %s tenant.", tenantDomain);
            throw new I18nEmailMgtServerException(error, e);
        }

        return templateList;
    }

    @Override
    public EmailTemplate getEmailTemplate(String templateDisplayName, String locale, String tenantDomain) throws
            I18nEmailMgtException {

        EmailTemplate emailTemplate = null;

        validateEmailTemplateType(templateDisplayName, tenantDomain);
        validateLocale(locale);

        String templateDirectory = I18nEmailUtil.getNormalizedName(templateDisplayName);
        String path = EMAIL_TEMPLATE_PATH + PATH_SEPARATOR + templateDirectory;

        try {
            Resource emailResource = resourceMgtService.getIdentityResource(path, tenantDomain, locale);
            if (emailResource != null) {
                emailTemplate = I18nEmailUtil.getEmailTemplate(emailResource);
            }
        } catch (IdentityRuntimeException ex) {
            String error = "Error when retrieving '%s:%s' template from %s tenant registry.";
            handleServerException(String.format(error, templateDisplayName, locale, tenantDomain), ex);
        }

        // Oops! we don't have the requested email template type in required locale for this tenantDomain.
        if (emailTemplate == null) {
            if (StringUtils.equalsIgnoreCase(DEFAULT_EMAIL_LOCALE, locale)) {
                // This means the template type is not there even in the default locale. We need to break the flow at
                // the consuming side or else will end up with a NPE.
                String error =  "Cannot find '%s' template in the default '%s' locale for '%s' tenant.";
                throw new I18nEmailMgtInternalException(I18nMgtConstants.ErrorCodes.EMAIL_TEMPLATE_TYPE_NODE_FOUND,
                        String.format(error, templateDisplayName, locale, tenantDomain), null);
            } else {
                // We try to get the template type in our default locale : en_US
                if (log.isDebugEnabled()) {
                    String msg = "'%s' template in '%s' locale was not found in '%s' tenant. Trying to return the " +
                            "template in default locale : '%s'";
                    log.debug(String.format(msg, templateDisplayName, locale, tenantDomain, DEFAULT_EMAIL_LOCALE));
                }
                return getEmailTemplate(templateDisplayName, DEFAULT_EMAIL_LOCALE, tenantDomain);
            }
        }

        return emailTemplate;
    }

    @Override
    public void addEmailTemplate(EmailTemplate emailTemplate, String tenantDomain) throws I18nEmailMgtException {

        // validate the email template object before processing it.
        validateEmailTemplate(emailTemplate, tenantDomain);

        Resource templateResource = I18nEmailUtil.createTemplateResource(emailTemplate);
        String templateTypeDisplayName = emailTemplate.getTemplateDisplayName();
        String templateType = I18nEmailUtil.getNormalizedName(templateTypeDisplayName);
        String locale = emailTemplate.getLocale();

        String path = EMAIL_TEMPLATE_PATH + PATH_SEPARATOR + templateType; // template type root directory
        try {
            // check whether a template type root directory exists
            if (!resourceMgtService.isResourceExists(path, tenantDomain)) {
                // we add new template type with relevant properties
                addEmailTemplateType(templateTypeDisplayName, tenantDomain);
                if (log.isDebugEnabled()) {
                    String msg = "Creating template type %s in %s tenant registry.";
                    log.debug(String.format(msg, templateTypeDisplayName, tenantDomain));
                }
            }

            resourceMgtService.putIdentityResource(templateResource, path, tenantDomain, locale);
        } catch (IdentityRuntimeException ex) {
            String errorMsg = "Error when adding new email template of %s type, %s locale to %s tenant registry.";
            handleServerException(String.format(errorMsg, templateResource, locale, tenantDomain), ex);
        }
    }


    @Override
    public void deleteEmailTemplate(String templateTypeName, String localeCode, String tenantDomain) throws
            I18nEmailMgtException {
        // validate the name and locale code.
        if (StringUtils.isBlank(templateTypeName)) {
            throw new I18nEmailMgtClientException("Cannot Delete template. Email displayName cannot be null.");
        }

        if (StringUtils.isBlank(localeCode)) {
            throw new I18nEmailMgtClientException("Cannot Delete template. Email locale cannot be null.");
        }

        String templateType = I18nEmailUtil.getNormalizedName(templateTypeName);
        String path = EMAIL_TEMPLATE_PATH + PATH_SEPARATOR + templateType;

        try {
            resourceMgtService.deleteIdentityResource(path, tenantDomain, localeCode);
        } catch (IdentityRuntimeException ex) {
            String msg = String.format("Error deleting %s:%s template from %s tenant registry.", templateTypeName,
                    localeCode, tenantDomain);
            handleServerException(msg, ex);
        }
    }

    @Override
    public void addDefaultEmailTemplates(String tenantDomain) throws I18nEmailMgtException {

        try {
            // load DTOs from the I18nEmailUtil class
            List<EmailTemplate> defaultTemplates = I18nEmailUtil.getDefaultEmailTemplates();
            // iterate through the list and write to registry!
            for (EmailTemplate emailTemplateDTO : defaultTemplates) {
                String templateTypeDisplayName = emailTemplateDTO.getTemplateDisplayName();
                String templateType = I18nEmailUtil.getNormalizedName(templateTypeDisplayName);

                String path = EMAIL_TEMPLATE_PATH + PATH_SEPARATOR + templateType; // template type root directory
                //Check for existence of each category, since some template may have migrated from earlier version
                //This will also add new template types provided from file, but won't update any existing
                if (!resourceMgtService.isResourceExists(path, tenantDomain)) {
                    try {
                        addEmailTemplate(emailTemplateDTO, tenantDomain);

                    } catch (DuplicateEmailTemplateException e) {
                        log.warn("Template" + templateTypeDisplayName + "already exists in the registry,Hence " +
                                "ignoring addition");
                    }
                    if (log.isDebugEnabled()) {
                        String msg = "Default template added to %s tenant registry : %n%s";
                        log.debug(String.format(msg, tenantDomain, emailTemplateDTO.toString()));
                    }
                }
            }

            if (log.isDebugEnabled()) {
                String msg = "Added %d default email templates to %s tenant registry";
                log.debug(String.format(msg, defaultTemplates.size(), tenantDomain));
            }
        } catch (IdentityRuntimeException ex) {
            String error = "Error when tried to check for default email templates in %s tenant registry";
            log.error(String.format(error, tenantDomain), ex);
        }
    }

    @Override
    public boolean isEmailTemplateExists(String templateTypeDisplayName, String locale, String tenantDomain)
            throws I18nEmailMgtException {

        // get template directory name from display name.
        String normalizedTemplateName = I18nEmailUtil.getNormalizedName(templateTypeDisplayName);
        String path = EMAIL_TEMPLATE_PATH + PATH_SEPARATOR + normalizedTemplateName +
                        PATH_SEPARATOR + locale.toLowerCase();

        try {
            Resource template = resourceMgtService.getIdentityResource(path, tenantDomain);
            return template != null;
        } catch (IdentityRuntimeException e) {
            String error = String.format("Error when retrieving email templates of %s tenant.", tenantDomain);
            throw new I18nEmailMgtServerException(error, e);
        }
    }

    @Override
    public boolean isEmailTemplateTypeExists(String templateTypeDisplayName, String tenantDomain)
            throws I18nEmailMgtException {

        // get template directory name from display name.
        String normalizedTemplateName = I18nEmailUtil.getNormalizedName(templateTypeDisplayName);
        String path = EMAIL_TEMPLATE_PATH + PATH_SEPARATOR + normalizedTemplateName;

        try {
            Resource templateType = resourceMgtService.getIdentityResource(path, tenantDomain);
            return templateType != null;
        } catch (IdentityRuntimeException e) {
            String error = String.format("Error when retrieving email templates of %s tenant.", tenantDomain);
            throw new I18nEmailMgtServerException(error, e);
        }
    }

    /**
     * Validate an EmailTemplate object before persisting it into tenant's registry.
     *
     * @param emailTemplate
     */
    private void validateEmailTemplate(EmailTemplate emailTemplate, String tenantDomain) throws
            I18nEmailMgtClientException {

        if (emailTemplate == null) {
            throw new I18nEmailMgtClientException("Email Template object cannot be null.");
        }

        String templateDisplayName = emailTemplate.getTemplateDisplayName();
        validateEmailTemplateType(templateDisplayName, tenantDomain);

        String normalizedTemplateDisplayName = I18nEmailUtil.getNormalizedName(templateDisplayName);
        if (!StringUtils.equalsIgnoreCase(normalizedTemplateDisplayName, emailTemplate.getTemplateType())) {
            emailTemplate.setTemplateType(normalizedTemplateDisplayName);
        }

        String locale = emailTemplate.getLocale();
        validateLocale(locale);

        // TODO validate content type?
        String subject = emailTemplate.getSubject();
        String body = emailTemplate.getBody();
        String footer = emailTemplate.getFooter();

        if (StringUtils.isBlank(subject) || StringUtils.isBlank(body) || StringUtils.isBlank(footer)) {
            throw new I18nEmailMgtClientException("subject/body/footer sections of email template cannot be empty.");
        }

    }

    /**
     * Validate the displayName of a email template type.
     *
     * @param templateDisplayName
     * @throws I18nEmailMgtClientException
     */
    private void validateEmailTemplateType(String templateDisplayName, String tenantDomain) throws
            I18nEmailMgtClientException {
        // check for null or empty
        if (StringUtils.isBlank(templateDisplayName)) {
            throw new I18nEmailMgtClientException("Email Template Type displayname cannot be null.");
        }

        // template name can contain only alphanumeric characters and spaces, it can't contain registry invalid
        // characters
        String[] whiteListPatterns = {TEMPLATE_REGEX_KEY};
        String[] blackListPatterns = {REGISTRY_INVALID_CHARS};
        if (!IdentityValidationUtil.isValid(templateDisplayName, whiteListPatterns, blackListPatterns)) {
            throw new I18nEmailMgtClientException("Invalid characters exists in the email template display name : " +
                    templateDisplayName);
        }
    }

    private void validateLocale(String localeCode) throws I18nEmailMgtClientException {

        if (StringUtils.isBlank(localeCode)) {
            throw new I18nEmailMgtClientException("Locale code cannot be empty or null");
        }

        // regex check for registry invalid chars.
        if (!IdentityValidationUtil.isValidOverBlackListPatterns(localeCode, REGISTRY_INVALID_CHARS)) {
            throw new I18nEmailMgtClientException("Locale string contains invalid characters : " + localeCode);
        }
    }

    private void handleServerException(String errorMsg, Throwable ex) throws I18nEmailMgtServerException {

        log.error(errorMsg);
        throw new I18nEmailMgtServerException(errorMsg, ex);
    }


}
